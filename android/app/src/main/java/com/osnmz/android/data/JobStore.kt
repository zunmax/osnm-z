package com.osnmz.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.File

/**
 * Persists job metadata only. The file jobs.json intentionally contains
 * NO private key field at all — see Job.toJson(). A key can only be added
 * through SecureKeyStore, on the phone itself.
 */
class JobStore(private val context: Context) {
    private val file: File get() = File(context.filesDir, "jobs.json")
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()
    /** Simple listener for the View UI (framework, no Flow collection needed). */
    var onChanged: (() -> Unit)? = null

    fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            val list = (0 until arr.length()).map { Job.fromJson(arr.getJSONObject(it)) }
                .map { job ->
                    // A run never survives a process death: never resume silently.
                    if (job.status in setOf(JobStatus.LOOKING_UP, JobStatus.WAITING, JobStatus.PREPARING, JobStatus.SUBMITTING, JobStatus.CONFIRMING))
                        job.copy(status = JobStatus.STOPPED, lastMessage = "Dihentikan saat app ditutup. Jalankan ulang untuk melanjutkan.")
                    else job
                }
            _jobs.value = list
        } catch (_: Exception) {
            _jobs.value = emptyList()
        }
    }

    private fun save(list: List<Job>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
        try { onChanged?.invoke() } catch (_: Exception) {}
    }

    fun upsert(job: Job) {
        _jobs.update { list ->
            val next = if (list.any { it.id == job.id }) list.map { if (it.id == job.id) job else it } else list + job
            save(next); next
        }
    }

    fun update(id: String, transform: (Job) -> Job) {
        _jobs.update { list ->
            val next = list.map { if (it.id == id) transform(it) else it }
            save(next); next
        }
    }

    fun get(id: String): Job? = _jobs.value.firstOrNull { it.id == id }

    fun remove(id: String) {
        _jobs.update { list ->
            val next = list.filterNot { it.id == id }
            save(next); next
        }
    }

    fun appendLog(id: String, line: String) {
        update(id) { job ->
            val trimmed = (job.logs + line).takeLast(100)
            job.copy(logs = trimmed, lastMessage = line)
        }
    }
}

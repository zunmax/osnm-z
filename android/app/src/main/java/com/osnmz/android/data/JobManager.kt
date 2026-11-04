package com.osnmz.android.data

import android.content.Context
import android.content.Intent
import com.osnmz.android.engine.MintEngine
import com.osnmz.android.security.SecureKeyStore
import com.osnmz.android.service.MintService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns all running jobs. One app-wide scope, one coroutine per job.
 * Different wallets run in parallel; same-wallet submissions are
 * serialised inside MintEngine via NonceLocks.
 */
class JobManager(private val context: Context) {
    val store = JobStore(context)
    val jobs: StateFlow<List<Job>> get() = store.jobs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<String, CoroutineJob>()

    fun isRunning(id: String): Boolean = running[id]?.isActive == true
    fun anyRunning(): Boolean = running.values.any { it.isActive }

    fun addJob(job: Job) = store.upsert(job)

    fun updateJob(job: Job) = store.upsert(job)

    fun deleteJob(job: Job) {
        stopJob(job.id)
        store.remove(job.id)
        // Deleting a job deletes its key. This is irreversible by design.
        SecureKeyStore.deleteKey(context, job.id)
    }

    fun stopJob(id: String) {
        val j = running.remove(id)
        scope.launch {
            j?.cancelAndJoin()
            store.update(id) { it.copy(status = JobStatus.STOPPED, lastMessage = "Dihentikan oleh user.") }
            refreshService()
        }
    }

    fun runDoctor(job: Job) {
        if (isRunning(job.id)) return
        startServiceIfNeeded()
        val cjob = scope.launch {
            store.update(job.id) { it.copy(status = JobStatus.PREPARING, lastMessage = "Doctor: memeriksa…") }
            try {
                val creds = SecureKeyStore.credentials(context, job.id)
                val engine = MintEngine(
                    onLog = { store.appendLog(job.id, it) },
                    onStatus = { s -> store.update(job.id) { it.copy(status = s) } },
                    onTxHash = {},
                )
                val msg = engine.doctor(job, creds)
                store.update(job.id) { it.copy(status = JobStatus.IDLE, lastMessage = msg) }
                store.appendLog(job.id, msg)
                NotificationHelper.event(context, "Doctor selesai: ${job.name}", msg)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                store.update(job.id) { it.copy(status = JobStatus.FAILED, lastMessage = "Doctor gagal: ${e.message}") }
                store.appendLog(job.id, "Doctor gagal: ${e.message}")
                NotificationHelper.event(context, "Doctor gagal: ${job.name}", e.message ?: "Doctor gagal")
            } finally {
                running.remove(job.id); refreshService()
            }
        }
        running[job.id] = cjob
    }

    fun runLookup(job: Job) {
        if (isRunning(job.id)) return
        startServiceIfNeeded()
        val cjob = scope.launch {
            try {
                val creds = try {
                    if (job.hasKey) SecureKeyStore.credentials(context, job.id) else null
                } catch (_: Exception) { null }
                val engine = MintEngine(
                    onLog = { store.appendLog(job.id, it) },
                    onStatus = { s -> store.update(job.id) { it.copy(status = s) } },
                    onTxHash = {},
                    onStages = { stages, meta ->
                        store.update(job.id) {
                            it.copy(
                                stages = stages, collectionSlug = meta.slug,
                                collectionAddress = meta.address, collectionChainId = meta.networkId,
                                dropAddress = meta.dropAddress,
                                status = JobStatus.IDLE,
                                lastMessage = "Lookup OK: ${meta.slug}, ${stages.size} stage.",
                            )
                        }
                    },
                )
                val meta = engine.lookup(job, creds)
                val selectable = meta.stages.count { it.isSelectable == true }
                val msg = "Lookup OK: ${meta.slug}, ${meta.stages.size} stage, $selectable bisa dipilih."
                store.appendLog(job.id, msg)
                NotificationHelper.event(context, "Lookup selesai: ${job.name}", msg)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                store.update(job.id) { it.copy(status = JobStatus.FAILED, lastMessage = "Lookup gagal: ${e.message}") }
                store.appendLog(job.id, "Lookup gagal: ${e.message}")
                NotificationHelper.event(context, "Lookup gagal: ${job.name}", e.message ?: "Lookup gagal")
            } finally {
                running.remove(job.id); refreshService()
            }
        }
        running[job.id] = cjob
    }

    fun runMint(job: Job) {
        if (isRunning(job.id)) return
        val latest = store.get(job.id) ?: job
        if (latest.collectionSlug == null || latest.stageIndex == null) {
            store.update(job.id) { it.copy(status = JobStatus.FAILED, lastMessage = "Lookup dulu dan pilih stage sebelum Start.") }
            return
        }
        if (!latest.hasKey || latest.walletAddress == null) {
            store.update(job.id) { it.copy(status = JobStatus.FAILED, lastMessage = "Import private key dulu untuk job ini.") }
            return
        }
        startServiceIfNeeded()
        NotificationHelper.event(context, "Job dimulai: ${job.name}", "Stage: ${latest.stageName ?: "-"} — menunggu / menyiapkan mint.")
        val cjob = scope.launch {
            try {
                // Re-resolve metadata fresh at run time, but keep the user's
                // chosen stage index.
                val creds = SecureKeyStore.credentials(context, job.id)
                val engine = MintEngine(
                    onLog = { store.appendLog(job.id, it) },
                    onStatus = { s -> store.update(job.id) { it.copy(status = s) } },
                    onTxHash = { h ->
                        store.update(job.id) { it.copy(lastTxHash = h) }
                        store.appendLog(job.id, "Tx terkirim: $h")
                        NotificationHelper.event(context, "Tx terkirim: ${job.name}", h)
                    },
                )
                val meta = engine.lookup(latest, creds)
                // lookup() overwrote stages; re-apply the chosen stage.
                val current = store.get(job.id) ?: latest
                val metaWithChoice = meta
                val jobToRun = current.copy(
                    stageIndex = latest.stageIndex,
                    stageName = latest.stageName,
                    stageType = latest.stageType,
                    stageStartMs = latest.stageStartMs,
                    stageEndMs = latest.stageEndMs,
                )
                // The stage times in meta are authoritative; find the stage.
                val stage = metaWithChoice.stages.firstOrNull { it.index == latest.stageIndex }
                val finalJob = if (stage != null) jobToRun.copy(
                    stageName = stage.label, stageType = stage.type,
                    stageStartMs = stage.startMs, stageEndMs = stage.endMs,
                ) else jobToRun
                engine.run(finalJob, creds, metaWithChoice)
                // Success path (previously silent: no event at all). The
                // engine already set CONFIRMED / UNCONFIRMED via onStatus.
                val done = store.get(job.id)
                when (done?.status) {
                    JobStatus.CONFIRMED -> NotificationHelper.event(
                        context, "Mint terkonfirmasi: ${job.name}",
                        done.lastTxHash ?: "Cek log job untuk tx hash.",
                    )
                    JobStatus.UNCONFIRMED -> NotificationHelper.event(
                        context, "Mint belum terkonfirmasi: ${job.name}",
                        "Tx terkirim tapi receipt belum datang: ${done.lastTxHash ?: "-"} — cek di explorer sebelum retry.",
                    )
                    else -> {}
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    store.update(job.id) { it.copy(status = JobStatus.STOPPED, lastMessage = "Dihentikan.") }
                    throw e
                }
                val cur = store.get(job.id)
                if (cur?.status !in setOf(JobStatus.CONFIRMED, JobStatus.UNCONFIRMED)) {
                    store.update(job.id) { it.copy(status = JobStatus.FAILED, lastMessage = "Gagal: ${e.message}") }
                }
                store.appendLog(job.id, "Gagal: ${e.message}")
                NotificationHelper.event(context, "Job gagal: ${job.name}", e.message ?: "Mint gagal")
            } finally {
                running.remove(job.id); refreshService()
            }
        }
        running[job.id] = cjob
    }

    private fun startServiceIfNeeded() {
        try {
            val intent = Intent(context, MintService::class.java)
            context.startForegroundService(intent)
        } catch (_: Exception) {
        }
    }

    private fun refreshService() {
        if (!anyRunning()) {
            try { context.stopService(Intent(context, MintService::class.java)) } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile private var instance: JobManager? = null
        fun get(context: Context): JobManager =
            instance ?: synchronized(this) {
                instance ?: JobManager(context.applicationContext).also { it.store.load(); instance = it }
            }
    }
}

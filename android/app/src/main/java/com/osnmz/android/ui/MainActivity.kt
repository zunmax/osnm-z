package com.osnmz.android.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.osnmz.android.data.Job
import com.osnmz.android.data.JobManager
import com.osnmz.android.data.JobStatus
import com.osnmz.android.security.SecureKeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Framework-View UI (no Compose, no AndroidX UI) so the app can also be
 * built with the direct toolchain (kotlinc + d8 + aapt2) in environments
 * where the Gradle daemon cannot run. All widgets are created in code;
 * there are no XML layouts to merge.
 *
 * Private keys are never displayed: the key field is input-only and is
 * cleared after saving; only the masked address is ever shown.
 */
class MainActivity : Activity() {
    private lateinit var manager: JobManager
    private lateinit var jobsContainer: LinearLayout
    private val cards = mutableMapOf<String, CardViews>()

    private class CardViews(
        val root: LinearLayout,
        val status: TextView,
        val lastMessage: TextView,
        val tx: TextView,
        val stagesBox: LinearLayout,
        val logs: TextView,
        val startButton: Button,
        val lookupButton: Button,
        val doctorButton: Button,
        val stopButton: Button,
        var stagesSignature: String = "",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        manager = JobManager.get(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "OSNM-Z Multi-Job"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Port Android dari CLI osnm-z (OpenSea SeaDrop, satu wallet per job). " +
                "Private key hanya diinput di HP ini, disimpan terenkripsi di Android Keystore, " +
                "tidak pernah dikirim ke server mana pun, dan terhapus saat job dihapus. " +
                "Untuk background yang stabil, matikan optimasi baterai untuk app ini di Pengaturan HP."
        })
        root.addView(TextView(this).apply {
            text = "PERINGATAN: uji dulu dengan wallet kecil. Mint yang terkirim tidak bisa dibatalkan."
            setTextColor(Color.rgb(176, 0, 32))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        // If the user denied notifications once, Android will not show the
        // permission dialog again — the only fix is the system settings
        // page. This button goes straight there, instead of silently
        // dropping every event notification (the old bug).
        root.addView(Button(this).apply {
            text = "Buka Pengaturan Notifikasi (kalau notifikasi tidak muncul)"
            setOnClickListener {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                }
            }
        })
        root.addView(TextView(this).apply {
            text = "Cek juga di HP: Pengaturan > Aplikasi > OSNM-Z Multi-Job > Notifikasi harus ON untuk channel “Event mint” dan “Mint berjalan”, dan optimasi baterai dimatikan untuk app ini. Kalau izin ditolak, notifikasi event tidak akan muncul sama sekali — log job di dalam app tetap jadi sumber yang benar."
            textSize = 12f
        })
        root.addView(Button(this).apply {
            text = "+ Tambah Job"
            setOnClickListener {
                manager.addJob(Job(name = "Job ${manager.jobs.value.size + 1}"))
                refreshCards()
            }
        })
        jobsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(jobsContainer)
        root.addView(TextView(this).apply {
            text = "Status yang sudah jalan vs belum: Doctor, Lookup, phase publik, dan mint private " +
                "sudah di-port dari osnm-z. Yang belum penuh: warm-up koneksi pra-phase dan retry " +
                "calldata seagresif CLI (app memakai retry 15x sederhana), serta resume otomatis " +
                "setelah reboot/force-stop — job harus di-start ulang manual. Lihat README."
            textSize = 12f
        })

        manager.store.onChanged = { runOnUiThread { refreshCards() } }
        refreshCards()
    }

    override fun onResume() {
        super.onResume()
        refreshCards()
    }

    private fun refreshCards() {
        val jobs = manager.jobs.value
        // Remove cards for deleted jobs.
        val toRemove = cards.keys - jobs.map { it.id }.toSet()
        toRemove.forEach { id ->
            jobsContainer.removeView(cards[id]?.root)
            cards.remove(id)
        }
        for (job in jobs) {
            val existing = cards[job.id]
            if (existing == null) {
                val card = createCard(job)
                cards[job.id] = card
                jobsContainer.addView(card.root)
            } else {
                updateDynamic(job, existing)
            }
        }
    }

    private fun updateDynamic(job: Job, card: CardViews) {
        card.status.text = job.status.name
        card.lastMessage.text = "Terakhir: ${job.lastMessage}"
        card.tx.text = job.lastTxHash?.let { "Tx: $it" } ?: ""
        card.logs.text = job.logs.takeLast(30).joinToString("\n")
        val running = job.status in setOf(JobStatus.LOOKING_UP, JobStatus.WAITING, JobStatus.PREPARING, JobStatus.SUBMITTING, JobStatus.CONFIRMING)
        card.lookupButton.isEnabled = !running
        card.doctorButton.isEnabled = !running && job.hasKey
        val selectedStage = job.stageIndex?.let { idx -> job.stages.firstOrNull { it.index == idx } }
        val selectedSelectable = selectedStage?.let { it.isSelectable ?: (it.type.name == "PUBLIC_SALE") } ?: false
        card.startButton.isEnabled = !running && job.hasKey && job.stageIndex != null && selectedSelectable
        card.startButton.text = "Start Mint"
        card.stopButton.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        // Rebuild stage buttons only when the stage list/selection changed.
        // Format mirrors the CLI Phase selection exactly:
        //   "1. GTD | Allowlist | upcoming | ineligible | unavailable"
        //   "start=... | end=... | maks/wallet | sudah mint | harga"
        // Ineligible / unavailable stages are shown but DISABLED, so they
        // can never be selected for Start Mint (like the CLI, which only
        // accepts an eligible phase number).
        val sig = job.stages.joinToString("|") {
            "${it.index}:${it.label}:${it.type}:${it.eligibilityLabel}:${it.isSelectable}:${it.maxPerWallet}:${it.mintedQuantity}:${it.startMs}:${it.endMs}"
        } + "@${job.stageIndex}"
        if (sig != card.stagesSignature) {
            card.stagesSignature = sig
            card.stagesBox.removeAllViews()
            if (job.stages.isNotEmpty()) {
                card.stagesBox.addView(TextView(this).apply { text = "Pilih stage (seperti terminal osnm-z):"; setTypeface(null, android.graphics.Typeface.BOLD) })
                if (!job.hasKey) {
                    card.stagesBox.addView(TextView(this).apply {
                        text = "Catatan: eligibility wallet belum dicek karena key belum disimpan. Hanya PUBLIC yang ditandai eligible (public). Simpan key lalu Lookup ulang untuk cek allowlist."
                        textSize = 12f
                    })
                }
                for ((pos, st) in job.stages.withIndex()) {
                    val selected = job.stageIndex == st.index
                    val typeName = if (st.type.name == "PUBLIC_SALE") "Public" else "Allowlist"
                    val state = st.stateLabel()
                    val elig = st.eligibilityLabel ?: if (st.type.name == "PUBLIC_SALE") "eligible (public)" else "belum dicek"
                    val avail = st.availabilityLabel()
                    val time = "start=${st.startMs?.let { fmtTime(it) } ?: "open"} | end=${st.endMs?.let { fmtTime(it) } ?: "no end"}"
                    val limit = listOfNotNull(
                        st.maxPerWallet?.let { "maks/wallet: $it" },
                        st.mintedQuantity?.let { "sudah mint: $it" },
                        st.nativePriceWei?.let { "harga: $it wei" },
                    ).joinToString(" • ")
                    val selectable = st.isSelectable ?: (st.type.name == "PUBLIC_SALE")
                    card.stagesBox.addView(Button(this).apply {
                        text = "${pos + 1}. ${st.label ?: typeName} | $typeName | $state | $elig | $avail\n$time${if (limit.isNotEmpty()) "\n$limit" else ""}${if (!selectable) "\n(tidak bisa dipilih)" else ""}"
                        isEnabled = !running && selectable
                        setOnClickListener {
                            manager.updateJob(job.copy(
                                stageIndex = st.index, stageName = st.label,
                                stageType = st.type, stageStartMs = st.startMs, stageEndMs = st.endMs,
                            ))
                        }
                    })
                    // Note: an ineligible selected stage is NOT auto-cleared
                    // here (that would recurse through refreshCards); Start
                    // Mint is simply disabled for it above, like the CLI
                    // refusing an ineligible phase number.
                }
            }
        }
    }

    private fun editText(hint: String, initial: String, onChange: (String) -> Unit): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(initial)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            })
        }
    }

    private fun createCard(job: Job): CardViews {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.rgb(240, 244, 248))
        }
        fun add(v: android.view.View) = root.addView(v)
        val title = TextView(this).apply { text = job.name; setTypeface(null, android.graphics.Typeface.BOLD); textSize = 16f }
        add(title)
        val status = TextView(this).apply { text = job.status.name }
        add(status)
        add(TextView(this).apply { text = "Wallet: ${job.maskedAddress()}  •  Key tersimpan: ${if (job.hasKey) "Ya" else "Belum"}" })
        val lastMessage = TextView(this).apply { text = "Terakhir: ${job.lastMessage}" }
        add(lastMessage)
        val tx = TextView(this)
        add(tx)

        fun update(t: (Job) -> Job) {
            val cur = manager.store.get(job.id) ?: job
            manager.updateJob(t(cur))
            title.text = (manager.store.get(job.id)?.name ?: job.name)
        }
        add(editText("Nama job", job.name) { v -> update { it.copy(name = v) } })
        add(editText("Slug / URL OpenSea / alamat kontrak", job.locator) { v -> update { it.copy(locator = v) } })
        add(editText("RPC URL (HTTPS)", job.rpcUrl) { v -> update { it.copy(rpcUrl = v) } })
        add(editText("Quantity", job.quantity.toString()) { v -> v.toIntOrNull()?.let { q -> update { it.copy(quantity = q) } } })
        add(editText("Gas limit", job.gasLimit.toString()) { v -> v.toLongOrNull()?.let { g -> update { it.copy(gasLimit = g) } } })
        val feeSwitch = Switch(this).apply {
            text = "Fee otomatis"
            isChecked = job.feeAutomatic
            setOnCheckedChangeListener { _, checked -> update { it.copy(feeAutomatic = checked) } }
        }
        add(feeSwitch)
        val maxFeeField = editText("Max fee (gwei, bila manual)", job.maxFeeGwei) { v -> update { it.copy(maxFeeGwei = v) } }
        val priField = editText("Priority fee (gwei, bila manual)", job.maxPriorityFeeGwei) { v -> update { it.copy(maxPriorityFeeGwei = v) } }
        maxFeeField.visibility = if (job.feeAutomatic) android.view.View.GONE else android.view.View.VISIBLE
        priField.visibility = maxFeeField.visibility
        feeSwitch.setOnCheckedChangeListener { _, checked ->
            update { it.copy(feeAutomatic = checked) }
            maxFeeField.visibility = if (checked) android.view.View.GONE else android.view.View.VISIBLE
            priField.visibility = maxFeeField.visibility
        }
        add(maxFeeField); add(priField)
        add(editText("Public broadcast offset (ms, 0-60000)", job.broadcastOffsetMs.toString()) { v ->
            v.toLongOrNull()?.let { o -> update { it.copy(broadcastOffsetMs = o) } }
        })

        if (!job.hasKey) {
            val keyField = editText("Private key (hanya di HP ini, tidak ditampilkan lagi)", "") {}
            add(keyField)
            val keyError = TextView(this).apply { setTextColor(Color.rgb(176, 0, 32)) }
            add(keyError)
            add(Button(this).apply {
                text = "Simpan Key (terenkripsi di HP)"
                setOnClickListener {
                    try {
                        val address = SecureKeyStore.storeKey(this@MainActivity, job.id, keyField.text.toString())
                        manager.updateJob((manager.store.get(job.id) ?: job).copy(hasKey = true, walletAddress = address))
                        keyField.setText("")
                        refreshCards()
                    } catch (e: Exception) {
                        keyError.text = e.message ?: "Gagal menyimpan key"
                    }
                }
            })
        } else {
            add(Button(this).apply {
                text = "Hapus key job ini"
                setOnClickListener {
                    try { SecureKeyStore.deleteKey(this@MainActivity, job.id) } catch (_: Exception) {}
                    manager.updateJob((manager.store.get(job.id) ?: job).copy(hasKey = false, walletAddress = null))
                    refreshCards()
                }
            })
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lookupButton = Button(this).apply { text = "Lookup"; setOnClickListener { manager.runLookup(manager.store.get(job.id) ?: job) } }
        val doctorButton = Button(this).apply { text = "Doctor"; setOnClickListener { manager.runDoctor(manager.store.get(job.id) ?: job) } }
        val startButton = Button(this).apply {
            text = "Start Mint"
            setOnClickListener { manager.runMint(manager.store.get(job.id) ?: job) }
        }
        val stopButton = Button(this).apply {
            text = "Stop"
            visibility = android.view.View.GONE
            setOnClickListener { manager.stopJob(job.id) }
        }
        val deleteButton = Button(this).apply {
            text = "Hapus Job"
            setOnClickListener { manager.deleteJob(manager.store.get(job.id) ?: job) }
        }
        btnRow.addView(lookupButton); btnRow.addView(doctorButton); btnRow.addView(startButton)
        btnRow.addView(stopButton); btnRow.addView(deleteButton)
        add(btnRow)

        val stagesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(stagesBox)
        val logs = TextView(this).apply { textSize = 11f }
        add(TextView(this).apply { text = "Log job:"; setTypeface(null, android.graphics.Typeface.BOLD) })
        add(logs)

        val card = CardViews(root, status, lastMessage, tx, stagesBox, logs, startButton, lookupButton, doctorButton, stopButton)
        updateDynamic(job, card)
        return card
    }

    private fun fmtTime(ms: Long): String = try {
        // Seconds matter for a mint countdown, and the zone must be explicit
        // (the CLI prints UTC ISO; the app prints local time, so say so).
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms)) + " (waktu HP)"
    } catch (_: Exception) { ms.toString() }
}

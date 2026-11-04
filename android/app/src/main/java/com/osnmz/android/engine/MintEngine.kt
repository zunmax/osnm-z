package com.osnmz.android.engine

import com.osnmz.android.data.Job
import com.osnmz.android.data.JobStatus
import com.osnmz.android.data.StageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.math.BigInteger
import java.time.Instant

/**
 * One job's execution, ported from osnm-z command.py + execution.py +
 * submission.py, with the timing policy simplified for a phone:
 * - a future phase is waited out in the foreground service (not an exact
 *   alarm — see README for the Doze/OEM caveat),
 * - public broadcast offset is honoured (send at start - offset),
 * - a submitted transaction is never resent; an unconfirmed result keeps
 *   its hash so the user can check it on-chain.
 *
 * Nonce safety: [nonceLocks] serialises snapshot+sign+send per wallet
 * address across jobs, exactly the guard the CLI warns about manually.
 */
object NonceLocks {
    private val locks = mutableMapOf<String, Mutex>()
    @Synchronized fun forAddress(address: String): Mutex =
        locks.getOrPut(address.lowercase()) { Mutex() }
}

class MintEngine(
    private val onLog: (String) -> Unit,
    private val onStatus: (JobStatus) -> Unit,
    private val onTxHash: (String) -> Unit,
    private val onStages: (List<com.osnmz.android.data.StageInfo>, CollectionMeta) -> Unit = { _, _ -> },
) {
    private fun log(msg: String) = onLog(msg)

    fun parseGwei(text: String): BigInteger {
        val t = text.trim()
        require(t.isNotEmpty()) { "Fee manual kosong" }
        val parts = t.split(".")
        require(parts.size <= 2 && parts[0].all { it.isDigit() }) { "Fee gwei tak valid" }
        val whole = BigInteger(parts[0])
        val frac = if (parts.size == 2) parts[1].padEnd(9, '0').take(9) else "0"
        require(parts.size < 2 || parts[1].all { it.isDigit() }) { "Fee gwei tak valid" }
        return whole.multiply(BigInteger("1000000000")).add(BigInteger(frac))
    }

    suspend fun doctor(job: Job, creds: WalletCreds): String {
        require(job.rpcUrl.startsWith("https://")) { "RPC_URL harus HTTPS" }
        val rpc = RpcClient(job.rpcUrl)
        val chainId = rpc.chainId()
        val snap = rpc.snapshot(creds.address)
        val feeText = if (job.feeAutomatic) {
            "otomatis: maxFee=${snap.feeEstimate.maxFeePerGas} wei, priority=${snap.feeEstimate.maxPriorityFeePerGas} wei"
        } else {
            val max = parseGwei(job.maxFeeGwei); val pri = parseGwei(job.maxPriorityFeeGwei)
            require(max >= pri) { "max fee harus >= priority fee" }
            "manual: maxFee=$max wei, priority=$pri wei"
        }
        return "Doctor OK — chain=$chainId, wallet=${creds.address}, balance=${snap.balance} wei, nonce=${snap.pendingNonce}, $feeText"
    }

    /**
     * Lookup, now mirroring the CLI order: metadata first, then (when a
     * wallet key is available) SIWE sign-in + eligibility, so the stage
     * list can show eligible / ineligible / available exactly like the
     * terminal's Phase selection. Without a key, or if eligibility fails,
     * it falls back to public-only labels (PUBLIC = eligible (public),
     * allowlist = ineligible) and says so in the log instead of failing
     * the whole Lookup.
     */
    suspend fun lookup(job: Job, creds: WalletCreds? = null): CollectionMeta {
        onStatus(JobStatus.LOOKING_UP)
        require(job.rpcUrl.startsWith("https://")) { "RPC_URL harus HTTPS" }
        val rpc = RpcClient(job.rpcUrl)
        val chainId = rpc.chainId()
        log("RPC chain: $chainId")
        val sea = OpenSeaClient()
        val meta = sea.resolveCollection(job.locator, chainId)
        log("Koleksi: ${meta.slug} (${meta.address}), ${meta.stages.size} stage")
        val stages = if (creds != null) {
            try {
                sea.authenticate(creds, chainId, meta.slug)
                log("OpenSea SIWE sign-in OK — memeriksa eligibility wallet…")
                val data = sea.eligibility(meta.slug, creds.address)
                val enriched = sea.enrichWithEligibility(meta, creds.address, data)
                log("Eligibility wallet dicek: ${enriched.count { it.isSelectable == true }} dari ${enriched.size} stage bisa dipilih.")
                enriched
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("Eligibility wallet gagal (${e.message}); pakai label public-only: hanya PUBLIC yang ditandai eligible (public). Simpan key & Lookup ulang bila perlu.")
                sea.publicOnlyStages(meta)
            }
        } else {
            log("Tanpa key, eligibility wallet tidak bisa dicek: hanya PUBLIC yang ditandai eligible (public). Simpan key job ini lalu Lookup ulang untuk cek allowlist.")
            sea.publicOnlyStages(meta)
        }
        val enrichedMeta = meta.copy(stages = stages)
        onStages(stages, enrichedMeta)
        return enrichedMeta
    }

    suspend fun run(job: Job, creds: WalletCreds, meta: CollectionMeta) {
        val runStartMs = System.currentTimeMillis()
        val stageIdx = job.stageIndex ?: throw IllegalStateException("Pilih stage dulu lewat Lookup.")
        val stage = meta.stages.firstOrNull { it.index == stageIdx }
            ?: throw IllegalStateException("Stage tidak ditemukan di metadata terbaru.")
        val rpc = RpcClient(job.rpcUrl)
        val chainId = rpc.chainId()
        if (chainId != meta.networkId) throw IllegalStateException("Chain RPC berubah ($chainId != ${meta.networkId})")
        val wallet = Abi.checksum(creds.address)
        val sea = OpenSeaClient()

        // Scheduled wait (foreground service keeps this alive; see README).
        val startMs = stage.startMs
        if (startMs != null && startMs > System.currentTimeMillis()) {
            onStatus(JobStatus.WAITING)
            val waitSec = (startMs - System.currentTimeMillis()) / 1000
            log("Menunggu phase mulai dalam ${waitSec}s (${Instant.ofEpochMilli(startMs)})")
            var lastLoggedMinute = -1L
            while (System.currentTimeMillis() < startMs) {
                val remaining = startMs - System.currentTimeMillis()
                val minute = remaining / 60000
                if (minute != lastLoggedMinute && (remaining <= 10000 || minute != lastLoggedMinute)) {
                    // Log at most once a minute, plus the final 10 seconds.
                    if (remaining <= 10000 || lastLoggedMinute == -1L || minute < lastLoggedMinute) {
                        log("Countdown: ${remaining / 1000}s menuju phase")
                    }
                    lastLoggedMinute = minute
                }
                delay(minOf(1000L, remaining.coerceAtLeast(1L)))
            }
        }
        if (stage.endMs != null && System.currentTimeMillis() > stage.endMs) {
            throw IllegalStateException("Phase sudah berakhir")
        }
        onStatus(JobStatus.PREPARING)
        val isPublic = stage.type == StageType.PUBLIC_SALE

        // Build the mint action.
        val action: MintAction
        val publicCtx: SeaDrop.PublicContext?
        if (isPublic) {
            // Discover SeaDrop from OpenSea config-change receipts, fallback canonical.
            var seadrop = SeaDrop.CANONICAL
            try {
                val hashes = sea.configChangeHashes(meta.slug)
                log("SeaDrop discovery: ${hashes.size} config tx ditemukan")
                val topic0 = Crypto.keccak256Hex(
                    "PublicDropUpdated(address,(uint80,uint48,uint48,uint16,uint16,bool))"
                )
                val nftTopic = "0x" + meta.address.removePrefix("0x").lowercase().padStart(64, '0')
                outer@ for (h in hashes) {
                    val receipt = rpc.receipt(h) ?: continue
                    // receipt() only returns mined receipts; need raw logs, re-fetch:
                    val raw = rpc.call("eth_getTransactionReceipt", org.json.JSONArray().put(h))
                    if (raw.isNull("result")) continue
                    val logs = raw.getJSONObject("result").optJSONArray("logs") ?: continue
                    for (i in 0 until logs.length()) {
                        val lg = logs.getJSONObject(i)
                        val topics = lg.optJSONArray("topics") ?: continue
                        if (topics.length() >= 2 && topics.getString(0).equals(topic0, ignoreCase = true)
                            && topics.getString(1).equals(nftTopic, ignoreCase = true)
                            && lg.optString("status", "0x1") != "0x0"
                        ) {
                            // Receipt success is checked via status field of the receipt itself.
                            if (raw.getJSONObject("result").optString("status", "0x1") == "0x1") {
                                seadrop = lg.getString("address"); break@outer
                            }
                        }
                    }
                }
                log("SeaDrop target: $seadrop")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("SeaDrop discovery gagal, pakai canonical: ${e.message}")
            }
            val (ctx, stats) = SeaDrop.resolvePublicContext(rpc, meta.address, wallet, seadrop)
            publicCtx = ctx
            val nowSec = System.currentTimeMillis() / 1000
            if (nowSec >= ctx.config.endSec) throw IllegalStateException("Public drop on-chain sudah berakhir")
            action = SeaDrop.buildPublicMintAction(meta.address, ctx, stats, wallet, job.quantity)
            log("Public mint: harga ${ctx.config.mintPrice} wei x ${job.quantity}, jatah tersisa ${ctx.config.maxPerWallet - stats.mintedByWallet}")
        } else {
            publicCtx = null
            // Private: SIWE auth, then mint action (retry + reauth, bounded).
            sea.authenticate(creds, chainId, meta.slug)
            log("OpenSea SIWE sign-in OK")
            var lastErr: Exception? = null
            var found: MintAction? = null
            for (attempt in 1..15) {
                try {
                    val cand = sea.mintAction(meta, wallet, job.quantity)
                    val idx = SeaDrop.stageIndexOfExact(cand)
                    if (idx == stageIdx) { found = cand; break }
                    log("OpenSea mengembalikan stage $idx, retry untuk stage $stageIdx (percobaan $attempt)")
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    lastErr = e
                    log("Mint action percobaan $attempt gagal: ${e.message}")
                    try { sea.authenticate(creds, chainId, meta.slug) } catch (_: Exception) {}
                }
                delay(250)
            }
            action = found ?: throw IllegalStateException("OpenSea tidak mengembalikan mint data untuk stage ini: ${lastErr?.message}")
            log("Private mint action OK: to=${action.to}, value=${action.value} wei")
        }

        // Nonce-locked snapshot + sign + send for this wallet.
        val txHash: String
        NonceLocks.forAddress(wallet).withLock {
            onStatus(JobStatus.SUBMITTING)
            val snap = rpc.snapshot(wallet)
            val fees = if (job.feeAutomatic) {
                // osnm-z: 1.25x immediate, 2.5x scheduled. Scheduled here means
                // the phase was still in the future when this run started.
                val wasScheduled = startMs != null && startMs > runStartMs
                val bps = if (wasScheduled) 25000L else 12500L
                val maxFee = snap.feeEstimate.maxFeePerGas.multiply(BigInteger.valueOf(bps)).add(BigInteger.valueOf(9999)).divide(BigInteger.valueOf(10000))
                val pri = snap.feeEstimate.maxPriorityFeePerGas.multiply(BigInteger.valueOf(bps)).add(BigInteger.valueOf(9999)).divide(BigInteger.valueOf(10000))
                FeeEstimate(pri, maxFee)
            } else {
                val max = parseGwei(job.maxFeeGwei); val pri = parseGwei(job.maxPriorityFeeGwei)
                require(max >= pri) { "max fee harus >= priority fee" }
                FeeEstimate(pri, max)
            }
            val totalNeeded = action.value.add(fees.maxFeePerGas.multiply(BigInteger.valueOf(job.gasLimit)))
            require(snap.balance >= totalNeeded) {
                "Saldo tidak cukup: butuh $totalNeeded wei (mint ${action.value} + gas), saldo ${snap.balance} wei"
            }
            val signed = Crypto.signEip1559(
                chainId, snap.pendingNonce, fees.maxPriorityFeePerGas, fees.maxFeePerGas,
                job.gasLimit, action.to, action.value, action.data, creds.privateKey,
            )
            val rawHex = Abi.toHex(signed.raw)
            // Public broadcast offset: send at start - offset.
            if (isPublic && job.broadcastOffsetMs > 0 && startMs != null) {
                val target = startMs - job.broadcastOffsetMs
                val wait = target - System.currentTimeMillis()
                if (wait > 0) {
                    log("Public broadcast offset: menunggu ${wait}ms sebelum kirim")
                    delay(wait)
                }
            }
            log("Mengirim tx (nonce=${snap.pendingNonce}) — hash lokal akan dicocokkan dengan RPC")
            txHash = rpc.sendRawTransaction(rawHex)
            require(txHash.equals(signed.hash, ignoreCase = true)) {
                "RPC mengembalikan hash berbeda dari hasil signing lokal"
            }
            onTxHash(txHash)
            log("Tx terkirim: $txHash")
        }

        // Receipt polling OUTSIDE the nonce lock so other wallets/jobs can proceed.
        onStatus(JobStatus.CONFIRMING)
        val deadline = System.currentTimeMillis() + job.receiptTimeoutSec * 1000
        var receipt: Pair<Boolean, org.json.JSONArray>? = null
        while (System.currentTimeMillis() < deadline) {
            receipt = rpc.receipt(txHash)
            if (receipt != null) break
            delay(1000)
        }
        if (receipt == null) {
            onStatus(JobStatus.UNCONFIRMED)
            log("UNCONFIRMED (tidak dikirim ulang): $txHash — cek hash ini di explorer sebelum retry.")
            return
        }
        val (ok, logsJson) = receipt
        if (!ok) {
            onStatus(JobStatus.FAILED)
            throw IllegalStateException("Transaksi revert di chain: $txHash")
        }
        // Verify ERC-721 Transfer logs exactly like osnm-z nft.py.
        val transferTopic = Crypto.keccak256Hex("Transfer(address,address,uint256)")
        val tokenIds = mutableListOf<BigInteger>()
        if (logsJson != null) {
            for (i in 0 until logsJson.length()) {
                val lg = logsJson.getJSONObject(i)
                if (!lg.optString("address").equals(meta.address, ignoreCase = true)) continue
                val topics = lg.optJSONArray("topics") ?: continue
                if (topics.length() != 4) continue
                if (!topics.getString(0).equals(transferTopic, ignoreCase = true)) continue
                val from = topics.getString(1).takeLast(40)
                val to = topics.getString(2).takeLast(40)
                if (from != "0000000000000000000000000000000000000000") continue
                if (!to.equals(wallet.removePrefix("0x"), ignoreCase = true)) continue
                if (lg.optString("data", "0x") != "0x") continue
                tokenIds.add(BigInteger(topics.getString(3).removePrefix("0x"), 16))
            }
        }
        if (tokenIds.size != job.quantity || tokenIds.toSet().size != tokenIds.size) {
            onStatus(JobStatus.FAILED)
            throw IllegalStateException("Receipt sukses tapi tidak membuktikan mint ${job.quantity} NFT ke wallet ini (tokenIds ditemukan: ${tokenIds.size}). Tx: $txHash")
        }
        onStatus(JobStatus.CONFIRMED)
        log("CONFIRMED — tokenId: ${tokenIds.joinToString(", ")} — tx: $txHash")
    }
}

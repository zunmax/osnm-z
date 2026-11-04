package com.osnmz.android.data

import org.json.JSONObject
import java.util.UUID

/** Status of one mint job. Kept intentionally small so the UI and the service agree. */
enum class JobStatus {
    IDLE, LOOKING_UP, WAITING, PREPARING, SUBMITTING, CONFIRMING,
    CONFIRMED, FAILED, STOPPED, UNCONFIRMED
}

enum class StageType { PUBLIC_SALE, SIGNED_PRESALE, MERKLE_PRESALE, UNKNOWN }

data class StageInfo(
    val index: Int,
    val label: String?,
    val type: StageType,
    val startMs: Long?,
    val endMs: Long?,
    val maxPerWallet: Long?,
    val nativePriceWei: java.math.BigInteger?,
    val priceChain: String?,
    val isEligible: Boolean?,
    val uuid: String?,
    // Filled by Lookup from the eligibility snapshot, mirroring the CLI's
    // PhaseOption fields. Null = eligibility was not checked (no key, or
    // the eligibility query failed and Lookup fell back to metadata only).
    val eligibilityLabel: String? = null,
    val isSelectable: Boolean? = null,
    val mintedQuantity: Long? = null,
) {
    /** Same state words as the CLI: active / upcoming / ended. */
    fun stateLabel(nowMs: Long = System.currentTimeMillis()): String {
        if (endMs != null && nowMs >= endMs) return if (type == StageType.PUBLIC_SALE) "requires on-chain timing check" else "ended"
        if (startMs != null && nowMs < startMs) return "upcoming"
        return "active"
    }

    fun availabilityLabel(): String {
        val sel = isSelectable
        if (sel != null) return if (sel) "available" else "unavailable"
        // Without an eligibility check, only PUBLIC can be assumed selectable.
        return if (type == StageType.PUBLIC_SALE) "available" else "unavailable"
    }
}

/**
 * One mint job. This object NEVER contains a private key.
 * The key lives only in SecureKeyStore, keyed by [id], and is loaded
 * into memory for the duration of a run, then dropped.
 */
data class Job(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Job baru",
    val locator: String = "",
    val quantity: Int = 1,
    val rpcUrl: String = "",
    val gasLimit: Long = 300000L,
    val feeAutomatic: Boolean = true,
    val maxFeeGwei: String = "",
    val maxPriorityFeeGwei: String = "",
    val broadcastOffsetMs: Long = 0L,
    val receiptTimeoutSec: Long = 120L,
    val walletAddress: String? = null,
    val hasKey: Boolean = false,
    val stageIndex: Int? = null,
    val stageName: String? = null,
    val stageType: StageType? = null,
    val stageStartMs: Long? = null,
    val stageEndMs: Long? = null,
    val stages: List<StageInfo> = emptyList(),
    val collectionSlug: String? = null,
    val collectionAddress: String? = null,
    val collectionChainId: Long? = null,
    val dropAddress: String? = null,
    val status: JobStatus = JobStatus.IDLE,
    val lastMessage: String = "Belum dijalankan.",
    val lastTxHash: String? = null,
    val logs: List<String> = emptyList(),
) {
    /** Address shown in UI/notifications: first 6 + last 4 chars only. Never the key. */
    fun maskedAddress(): String {
        val a = walletAddress ?: return "-"
        return if (a.length >= 10) "${a.take(6)}…${a.takeLast(4)}" else a
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("locator", locator)
        put("quantity", quantity); put("rpcUrl", rpcUrl); put("gasLimit", gasLimit)
        put("feeAutomatic", feeAutomatic); put("maxFeeGwei", maxFeeGwei)
        put("maxPriorityFeeGwei", maxPriorityFeeGwei)
        put("broadcastOffsetMs", broadcastOffsetMs)
        put("receiptTimeoutSec", receiptTimeoutSec)
        put("walletAddress", walletAddress ?: JSONObject.NULL)
        put("hasKey", hasKey)
        put("stageIndex", stageIndex ?: JSONObject.NULL)
        put("stageName", stageName ?: JSONObject.NULL)
        put("stageType", stageType?.name ?: JSONObject.NULL)
        put("stageStartMs", stageStartMs ?: JSONObject.NULL)
        put("stageEndMs", stageEndMs ?: JSONObject.NULL)
        put("collectionSlug", collectionSlug ?: JSONObject.NULL)
        put("collectionAddress", collectionAddress ?: JSONObject.NULL)
        put("collectionChainId", collectionChainId ?: JSONObject.NULL)
        put("dropAddress", dropAddress ?: JSONObject.NULL)
        put("status", status.name); put("lastMessage", lastMessage)
        put("lastTxHash", lastTxHash ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): Job {
            fun optStr(k: String): String? = if (o.isNull(k)) null else o.optString(k)
            fun optLong(k: String): Long? = if (o.isNull(k)) null else o.optLong(k)
            fun optInt(k: String): Int? = if (o.isNull(k)) null else o.optInt(k)
            return Job(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "Job baru"),
                locator = o.optString("locator", ""),
                quantity = o.optInt("quantity", 1),
                rpcUrl = o.optString("rpcUrl", ""),
                gasLimit = o.optLong("gasLimit", 300000L),
                feeAutomatic = o.optBoolean("feeAutomatic", true),
                maxFeeGwei = o.optString("maxFeeGwei", ""),
                maxPriorityFeeGwei = o.optString("maxPriorityFeeGwei", ""),
                broadcastOffsetMs = o.optLong("broadcastOffsetMs", 0L),
                receiptTimeoutSec = o.optLong("receiptTimeoutSec", 120L),
                walletAddress = optStr("walletAddress"),
                hasKey = o.optBoolean("hasKey", false),
                stageIndex = optInt("stageIndex"),
                stageName = optStr("stageName"),
                stageType = optStr("stageType")?.let { runCatching { StageType.valueOf(it) }.getOrNull() },
                stageStartMs = optLong("stageStartMs"),
                stageEndMs = optLong("stageEndMs"),
                collectionSlug = optStr("collectionSlug"),
                collectionAddress = optStr("collectionAddress"),
                collectionChainId = optLong("collectionChainId"),
                dropAddress = optStr("dropAddress"),
                status = runCatching { JobStatus.valueOf(o.optString("status", "IDLE")) }.getOrDefault(JobStatus.IDLE),
                lastMessage = o.optString("lastMessage", "Belum dijalankan."),
                lastTxHash = optStr("lastTxHash"),
            )
        }
    }
}

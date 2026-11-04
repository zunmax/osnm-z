package com.osnmz.android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

class RpcException(message: String) : Exception(message)

data class FeeEstimate(val maxPriorityFeePerGas: BigInteger, val maxFeePerGas: BigInteger)
data class WalletSnapshot(val balance: BigInteger, val pendingNonce: Long, val feeEstimate: FeeEstimate)

/**
 * Plain JSON-RPC client over framework HttpURLConnection (no OkHttp dep).
 * It never sees a private key: it only receives already-signed raw
 * transactions for eth_sendRawTransaction.
 */
class RpcClient(private val rpcUrl: String, private val timeoutMs: Int = 30000) {
    suspend fun call(method: String, params: JSONArray): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params)
        }.toString()
        val conn = (URL(rpcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            doOutput = true
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
                ?: throw RpcException("RPC tanpa body")
            if (code !in 200..299) throw RpcException("RPC HTTP $code")
            val obj = JSONObject(text)
            if (obj.has("error")) {
                val err = obj.getJSONObject("error")
                throw RpcException("RPC error ${err.optInt("code")}: ${err.optString("message")}")
            }
            obj
        } finally {
            conn.disconnect()
        }
    }

    private fun qty(obj: JSONObject): BigInteger {
        val v = obj.optString("result", "")
        require(v.startsWith("0x")) { "RPC quantity bukan hex" }
        return BigInteger(v.removePrefix("0x").ifEmpty { "0" }, 16)
    }

    suspend fun chainId(): Long = qty(call("eth_chainId", JSONArray())).toLong()

    suspend fun balance(address: String): BigInteger {
        val p = JSONArray().put(address).put("pending")
        return qty(call("eth_getBalance", p))
    }

    suspend fun pendingNonce(address: String): Long {
        val p = JSONArray().put(address).put("pending")
        return qty(call("eth_getTransactionCount", p)).toLong()
    }

    suspend fun feeEstimate(): FeeEstimate {
        val block = call("eth_getBlockByNumber", JSONArray().put("latest").put(false))
            .getJSONObject("result")
        val baseFeeHex = block.optString("baseFeePerGas", "")
        if (baseFeeHex.isEmpty()) throw RpcException("RPC tidak menyediakan baseFeePerGas (bukan EIP-1559?)")
        val baseFee = BigInteger(baseFeeHex.removePrefix("0x"), 16)
        val priority = qty(call("eth_maxPriorityFeePerGas", JSONArray()))
        // Sama seperti osnm-z rpc_protocol.decode_fee_inputs: max = base*2 + priority.
        return FeeEstimate(priority, baseFee.multiply(BigInteger.TWO).add(priority))
    }

    suspend fun snapshot(address: String): WalletSnapshot {
        val bal = balance(address)
        val nonce = pendingNonce(address)
        val fee = feeEstimate()
        return WalletSnapshot(bal, nonce, fee)
    }

    suspend fun ethCall(to: String, data: ByteArray): ByteArray {
        val params = JSONArray().put(JSONObject().apply {
            put("to", to); put("input", Abi.toHex(data))
        }).put("latest")
        val result = call("eth_call", params).optString("result", "")
        if (result.isEmpty() || result == "0x") throw RpcException("eth_call kosong dari $to")
        return Abi.fromHex(result)
    }

    suspend fun sendRawTransaction(rawHex: String): String {
        val result = call("eth_sendRawTransaction", JSONArray().put(rawHex)).optString("result", "")
        if (!result.matches(Regex("0x[0-9a-fA-F]{64}"))) throw RpcException("RPC mengembalikan hash tak valid")
        return result
    }

    /** Returns null while pending, otherwise (success, logsJson). */
    suspend fun receipt(txHash: String): Pair<Boolean, JSONArray>? {
        val obj = call("eth_getTransactionReceipt", JSONArray().put(txHash))
        if (obj.isNull("result")) return null
        val r = obj.getJSONObject("result")
        if (r.isNull("blockHash")) return null
        val status = r.optString("status", "0x0")
        val ok = BigInteger(status.removePrefix("0x"), 16) == BigInteger.ONE
        return ok to r.optJSONArray("logs")
    }
}

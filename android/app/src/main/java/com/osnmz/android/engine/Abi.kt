package com.osnmz.android.engine

import java.math.BigInteger

/** Minimal ABI helpers for exactly the calls this app makes. No generic encoder. */
object Abi {
    fun padAddress(hex: String): ByteArray {
        val clean = hex.removePrefix("0x").lowercase()
        require(clean.length == 40) { "address bukan 40 karakter hex" }
        val out = ByteArray(32)
        val raw = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        raw.copyInto(out, 20)
        return out
    }

    fun word(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        require(raw.size <= 32) { "nilai melebihi uint256" }
        val out = ByteArray(32)
        val src = if (raw.size == 33 && raw[0] == 0.toByte()) raw.copyOfRange(1, 33) else raw
        src.copyInto(out, 32 - src.size)
        return out
    }

    fun encodeAddressArg(address: String): ByteArray = padAddress(address)

    fun selector(hex4: String): ByteArray =
        hex4.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun toHex(bytes: ByteArray): String = "0x" + bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        require(clean.length % 2 == 0)
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun isAddress(value: String): Boolean =
        value.matches(Regex("0x[0-9a-fA-F]{40}"))

    fun checksum(address: String): String = Crypto.checksum(address)

    fun wordAt(data: ByteArray, index: Int): BigInteger {
        require(data.size >= (index + 1) * 32) { "data kontrak terlalu pendek" }
        return BigInteger(1, data.copyOfRange(index * 32, (index + 1) * 32))
    }
}

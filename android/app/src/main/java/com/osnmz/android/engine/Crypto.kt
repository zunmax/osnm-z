package com.osnmz.android.engine

import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger

/**
 * Minimal Ethereum crypto, implemented directly on BouncyCastle so the app
 * has exactly one third-party JAR (bcprov) and no web3j/OkHttp stack.
 * The private key is held here only as a BigInteger for the duration of a
 * run; this class never logs it and never serialises it.
 */
object Crypto {
    private val CURVE = ECNamedCurveTable.getParameterSpec("secp256k1")
    private val DOMAIN = ECDomainParameters(CURVE.curve, CURVE.g, CURVE.n, CURVE.h)
    private val N = CURVE.n
    private val HALF_N = N.shiftRight(1)

    fun keccak256(input: ByteArray): ByteArray {
        val d = KeccakDigest(256)
        d.update(input, 0, input.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }

    fun keccak256Hex(text: String): String = "0x" + keccak256(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Public key (uncompressed, without 0x04 prefix handling) -> address. */
    fun addressFromPrivateKey(priv: BigInteger): String {
        val q: ECPoint = CURVE.g.multiply(priv).normalize()
        val x = q.affineXCoord.encoded
        val y = q.affineYCoord.encoded
        val pub = ByteArray(64)
        x.copyInto(pub, 32 - x.size)
        y.copyInto(pub, 64 - y.size)
        val hash = keccak256(pub)
        val addr = hash.copyOfRange(12, 32).joinToString("") { "%02x".format(it) }
        return checksum("0x$addr")
    }

    fun checksum(address: String): String {
        val lower = address.removePrefix("0x").lowercase()
        require(lower.length == 40 && lower.all { it in "0123456789abcdef" }) { "alamat bukan 40 hex" }
        val hashHex = keccak256(lower.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val out = StringBuilder("0x")
        for (i in lower.indices) {
            val c = lower[i]
            out.append(if (c in 'a'..'f' && hashHex[i] >= '8') c.uppercaseChar() else c)
        }
        return out.toString()
    }

    data class Signature(val r: BigInteger, val s: BigInteger, val v: Long)

    /** ECDSA with RFC 6979 deterministic k, low-S enforced (as eth_account does). */
    private fun signHash(hash: ByteArray, priv: BigInteger): Pair<BigInteger, BigInteger> {
        val signer = ECDSASigner(HMacDSAKCalculator(org.bouncycastle.crypto.digests.SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(priv, DOMAIN))
        val sig = signer.generateSignature(hash)
        var r = sig[0]; var s = sig[1]
        if (s > HALF_N) s = N.subtract(s)
        return r to s
    }

    private fun recoveryId(hash: ByteArray, r: BigInteger, s: BigInteger, priv: BigInteger): Long {
        // Try recid 0/1 and pick the one whose recovered address matches the signer.
        val expected = addressFromPrivateKey(priv)
        for (recid in 0..1) {
            val recovered = recover(hash, r, s, recid) ?: continue
            if (recovered.equals(expected, ignoreCase = true)) return recid.toLong()
        }
        return 0L
    }

    private fun recover(hash: ByteArray, r: BigInteger, s: BigInteger, recid: Int): String? {
        return try {
            val x = r.add(BigInteger.valueOf((recid / 2).toLong()).multiply(N))
            if (x >= CURVE.curve.field.characteristic) return null
            val rPoint = decompress(x, recid and 1)
            val rInv = r.modInverse(N)
            val u1 = BigInteger(1, hash).multiply(rInv).negate().mod(N)
            val u2 = s.multiply(rInv).mod(N)
            val q = CURVE.g.multiply(u1).add(rPoint.multiply(u2)).normalize()
            val pub = ByteArray(64)
            val px = q.affineXCoord.encoded; val py = q.affineYCoord.encoded
            px.copyInto(pub, 32 - px.size); py.copyInto(pub, 64 - py.size)
            val h = keccak256(pub)
            checksum("0x" + h.copyOfRange(12, 32).joinToString("") { "%02x".format(it) })
        } catch (_: Exception) { null }
    }

    private fun decompress(x: BigInteger, yBit: Int): ECPoint {
        val xBytes = x.toByteArray()
        val enc = ByteArray(33)
        enc[0] = (0x02 + yBit).toByte()
        val src = if (xBytes.size == 33 && xBytes[0] == 0.toByte()) xBytes.copyOfRange(1, 33) else xBytes
        src.copyInto(enc, 33 - src.size)
        return CURVE.curve.decodePoint(enc).normalize()
    }

    /** EIP-191 personal-message signature, hex "0x" + r + s + v (v = 27/28). */
    fun signPersonalMessage(message: ByteArray, priv: BigInteger): String {
        val prefix = "\u0019Ethereum Signed Message:\n${message.size}".toByteArray(Charsets.UTF_8)
        val hash = keccak256(prefix + message)
        val (r, s) = signHash(hash, priv)
        val v = recoveryId(hash, r, s, priv) + 27
        return "0x" + r.toString(16).padStart(64, '0') + s.toString(16).padStart(64, '0') + v.toString(16).padStart(2, '0')
    }

    /** RLP encoding for the exact EIP-1559 transaction shape used here. */
    fun rlpEncode(item: Any): ByteArray {
        return when (item) {
            is ByteArray -> encodeBytes(item)
            is List<*> -> {
                val payload = item.fold(byteArrayOf()) { acc, el -> acc + rlpEncode(el!!) }
                encodeLength(payload, 0xc0)
            }
            is BigInteger -> encodeBytes(stripLeadingZero(item.toByteArray()))
            is Long -> encodeBytes(stripLeadingZero(BigInteger.valueOf(item).toByteArray()))
            is Int -> encodeBytes(stripLeadingZero(BigInteger.valueOf(item.toLong()).toByteArray()))
            is String -> encodeBytes(if (item.startsWith("0x")) Abi.fromHex(item) else item.toByteArray(Charsets.UTF_8))
            else -> throw IllegalArgumentException("RLP: tipe tak didukung ${item::class}")
        }
    }

    private fun stripLeadingZero(b: ByteArray): ByteArray {
        var bytes = b
        while (bytes.size > 1 && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        if (bytes.size == 1 && bytes[0] == 0.toByte()) return byteArrayOf()
        return bytes
    }

    private fun encodeBytes(data: ByteArray): ByteArray {
        return if (data.size == 1 && data[0].toInt() and 0xFF < 0x80) data
        else encodeLength(data, 0x80)
    }

    private fun encodeLength(data: ByteArray, offset: Int): ByteArray {
        return if (data.size < 56) {
            byteArrayOf((offset + data.size).toByte()) + data
        } else {
            val lenBytes = stripLeadingZero(BigInteger.valueOf(data.size.toLong()).toByteArray())
            byteArrayOf((offset + 55 + lenBytes.size).toByte()) + lenBytes + data
        }
    }

    data class SignedTx(val raw: ByteArray, val hash: String)

    /** EIP-1559 (type 2) signing: 0x02 || RLP([chainId, nonce, maxPriority, maxFee, gas, to, value, data, []]). */
    fun signEip1559(
        chainId: Long, nonce: Long, maxPriorityFeePerGas: BigInteger, maxFeePerGas: BigInteger,
        gasLimit: Long, to: String, value: BigInteger, data: ByteArray, priv: BigInteger,
    ): SignedTx {
        val toBytes = Abi.fromHex(to)
        val txList = listOf(
            BigInteger.valueOf(chainId), BigInteger.valueOf(nonce),
            maxPriorityFeePerGas, maxFeePerGas, BigInteger.valueOf(gasLimit),
            toBytes, value, data, emptyList<Any>(),
        )
        val signingHash = keccak256(rlpEncode(txList))
        val (r, s) = signHash(signingHash, priv)
        val recid = recoveryId(signingHash, r, s, priv)
        val yParity = recid // EIP-1559 v = yParity (0/1)
        val signedList = listOf(
            BigInteger.valueOf(chainId), BigInteger.valueOf(nonce),
            maxPriorityFeePerGas, maxFeePerGas, BigInteger.valueOf(gasLimit),
            toBytes, value, data, emptyList<Any>(),
            BigInteger.valueOf(yParity), r, s,
        )
        val raw = byteArrayOf(0x02) + rlpEncode(signedList)
        val hash = "0x" + keccak256(raw).joinToString("") { "%02x".format(it) }
        return SignedTx(raw, hash)
    }
}

/** Wallet material held in memory only during a run. Never logged/serialised. */
data class WalletCreds(val address: String, val privateKey: BigInteger) {
    companion object {
        fun fromHex(hex: String): WalletCreds {
            val clean = hex.trim().removePrefix("0x").removePrefix("0X")
            require(clean.length == 64 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "Private key harus 64 karakter hex."
            }
            val priv = BigInteger(clean, 16)
            require(priv > BigInteger.ZERO) { "Private key tak valid" }
            return WalletCreds(Crypto.addressFromPrivateKey(priv), priv)
        }
    }
}

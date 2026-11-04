package com.osnmz.android.engine

import java.math.BigInteger

object SeaDrop {
    const val CANONICAL = "0x00005EA00Ac477B1030CE78506496e8C2dE24bf5"
    const val FEE_RECIPIENT = "0x0000a26b00c1F0DF003000390027140000fAa719"
    val PUBLIC_MINT_SELECTOR = Abi.selector("161ac21f")
    private val GET_PUBLIC_DROP = Abi.selector("bc6a629c")
    private val GET_FEE_RECIPIENTS = Abi.selector("68632274")
    private val GET_MINT_STATS = Abi.selector("840e15d4")

    data class PublicDrop(
        val mintPrice: BigInteger, val startSec: Long, val endSec: Long,
        val maxPerWallet: Long, val restrictFeeRecipients: Boolean,
    )
    data class PublicContext(val target: String, val feeRecipient: String, val config: PublicDrop)
    data class MintStats(val mintedByWallet: Long, val totalSupply: Long, val maxSupply: Long)

    fun decodePublicDrop(data: ByteArray): PublicDrop {
        require(data.size == 192) { "SeaDrop mengembalikan data public drop tak valid" }
        val price = Abi.wordAt(data, 0)
        val start = Abi.wordAt(data, 1).toLong()
        val end = Abi.wordAt(data, 2).toLong()
        val max = Abi.wordAt(data, 3).toLong()
        val feeBps = Abi.wordAt(data, 4).toLong()
        val restricted = Abi.wordAt(data, 5) != BigInteger.ZERO
        require(end > start && max > 0 && feeBps <= 10000) { "Public drop tidak aktif/tak valid" }
        return PublicDrop(price, start, end, max, restricted)
    }

    fun decodeStats(data: ByteArray): MintStats {
        require(data.size == 96) { "Data mint stats tak valid" }
        return MintStats(Abi.wordAt(data, 0).toLong(), Abi.wordAt(data, 1).toLong(), Abi.wordAt(data, 2).toLong())
    }

    fun decodeAddressArray(data: ByteArray): List<String> {
        require(data.size >= 64 && data.size % 32 == 0)
        val offset = Abi.wordAt(data, 0).toLong()
        val count = Abi.wordAt(data, 1).toLong()
        require(offset == 32L && data.size.toLong() == 64 + count * 32)
        val out = mutableListOf<String>()
        for (i in 0 until count) {
            val word = data.copyOfRange(64 + i.toInt() * 32, 96 + i.toInt() * 32)
            require(word.copyOfRange(0, 12).all { it == 0.toByte() })
            out.add("0x" + word.copyOfRange(12, 32).joinToString("") { "%02x".format(it) })
        }
        return out
    }

    suspend fun resolvePublicContext(rpc: RpcClient, collectionAddress: String, wallet: String, seadrop: String): Pair<PublicContext, MintStats> {
        val nftArg = Abi.encodeAddressArg(collectionAddress)
        val dropData = rpc.ethCall(seadrop, GET_PUBLIC_DROP + nftArg)
        val config = decodePublicDrop(dropData)
        var feeRecipient = FEE_RECIPIENT
        if (config.restrictFeeRecipients) {
            val recData = rpc.ethCall(seadrop, GET_FEE_RECIPIENTS + nftArg)
            val recipients = decodeAddressArray(recData)
            require(recipients.isNotEmpty()) { "Public drop membatasi fee recipient tapi tidak ada yang dikonfigurasi" }
            feeRecipient = recipients.firstOrNull { it.equals(FEE_RECIPIENT, ignoreCase = true) } ?: recipients[0]
        }
        val statsData = rpc.ethCall(collectionAddress, GET_MINT_STATS + Abi.encodeAddressArg(wallet))
        val stats = decodeStats(statsData)
        return PublicContext(seadrop, feeRecipient, config) to stats
    }

    fun buildPublicMintAction(collectionAddress: String, ctx: PublicContext, stats: MintStats, wallet: String, quantity: Int): MintAction {
        val available = ctx.config.maxPerWallet - stats.mintedByWallet
        require(available >= quantity) { "Quantity melebihi sisa jatah wallet ($available)" }
        require(stats.totalSupply + quantity <= stats.maxSupply) { "Quantity melebihi sisa supply" }
        val value = ctx.config.mintPrice.multiply(BigInteger.valueOf(quantity.toLong()))
        val calldata = PUBLIC_MINT_SELECTOR +
            Abi.encodeAddressArg(collectionAddress) +
            Abi.encodeAddressArg(ctx.feeRecipient) +
            Abi.encodeAddressArg(wallet) +
            Abi.word(BigInteger.valueOf(quantity.toLong()))
        return MintAction(ctx.target, value, calldata)
    }

    /** Stage index inside a private SeaDrop mint call, mirroring osnm-z seadrop.py. */
    fun stageIndexOf(action: MintAction): Int {
        val sel = action.data.copyOfRange(0, 4)
        val privateSelectors = listOf(Abi.selector("4b61cd6f"), Abi.selector("4300a4e6"))
        return when {
            sel.contentEquals(PUBLIC_MINT_SELECTOR) -> {
                require(action.data.size >= 132) { "Calldata public mint terlalu pendek" }
                0
            }
            privateSelectors.any { sel.contentEquals(it) } -> {
                require(action.data.size >= 292) { "Calldata private mint terlalu pendek untuk membaca stage index" }
                Abi.wordAt(action.data.copyOfRange(4, action.data.size), 8).toInt()
                // Note: original reads bytes 260..292 of the full calldata.
                // That is word 8 of the full calldata (260/32 = 8.125 -> word index 8
                // starting at byte 256); the helper above is corrected below.
            }
            else -> throw IllegalArgumentException("Kontrak/fungsi mint tak didukung")
        }
    }

    /** Correct stage-index reader: bytes 260..292 of the full calldata. */
    fun stageIndexOfExact(action: MintAction): Int {
        val sel = action.data.copyOfRange(0, 4)
        if (sel.contentEquals(PUBLIC_MINT_SELECTOR)) return 0
        require(action.data.size >= 292) { "Calldata private mint terlalu pendek" }
        return BigInteger(1, action.data.copyOfRange(260, 292)).toInt()
    }
}

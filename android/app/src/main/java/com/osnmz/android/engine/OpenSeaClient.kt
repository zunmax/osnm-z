package com.osnmz.android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.BigInteger
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter

class OpenSeaException(message: String) : Exception(message)

data class CollectionMeta(
    val slug: String,
    val address: String,
    val chainIdentifier: String,
    val networkId: Long,
    val dropAddress: String,
    val stages: List<com.osnmz.android.data.StageInfo>,
)

data class MintAction(val to: String, val value: BigInteger, val data: ByteArray)

private const val SITE = "https://opensea.io"
private const val GRAPHQL = "https://gql.opensea.io/graphql"
private const val APP_ID = "os2-web"
private const val ELIGIBILITY_HASH = "b847627a1d94d694a9e69fd97f94ddccff0d4fb0f4936d5037fe608e000614f6"
private const val SIWE_STATEMENT =
    "Click to sign in and accept the OpenSea Terms of Service (https://opensea.io/tos) and Privacy Policy (https://opensea.io/privacy)."

/**
 * Port of osnm-z's WalletOpenSeaClient GraphQL flows (same queries, same
 * SIWE flow, same persisted-query fallback). The client holds cookies in
 * memory only; it never receives or stores a private key except transiently
 * inside [authenticate] for one local EIP-191 signature, which is exactly
 * what the CLI's signer does — the key itself is never sent to OpenSea.
 */
class OpenSeaClient {
    // In-memory cookies only (CookieManager per client instance, never persisted).
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private var authenticatedAddress: String? = null

    /** One POST/GET over framework HttpURLConnection, with in-memory cookies. */
    private fun httpJson(url: String, method: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000; readTimeout = 15000
            instanceFollowRedirects = false
            for ((k, v) in headers) setRequestProperty(k, v)
            // Match the CLI exactly: httpx sends "osnm-z/<version>" as the
            // default user-agent on every OpenSea request. The Android
            // port previously sent no user-agent at all.
            if (headers.keys.none { it.equals("user-agent", ignoreCase = true) }) {
                setRequestProperty("User-Agent", "osnm-z/0.1.0")
            }
            // Attach cookies stored for this host.
            val cookies = cookieManager.cookieStore.get(URI(url))
            if (cookies.isNotEmpty()) {
                setRequestProperty("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
            }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            if (body != null) OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            // Store Set-Cookie headers.
            conn.headerFields["Set-Cookie"]?.forEach { raw ->
                try { HttpCookie.parse(raw).forEach { cookieManager.cookieStore.add(URI(url), it) } } catch (_: Exception) {}
            }
            // Also handle case-insensitive header key variants.
            conn.headerFields.entries.firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }?.value?.forEach { raw ->
                try { HttpCookie.parse(raw).forEach { cookieManager.cookieStore.add(URI(url), it) } } catch (_: Exception) {}
            }
            return code to text
        } finally { conn.disconnect() }
    }

    private fun collectionUrl(slug: String) = "$SITE/collection/$slug/overview"

    private suspend fun graphqlPost(operation: String, query: String, variables: JSONObject, slug: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("operationName", operation); put("query", query); put("variables", variables)
            }.toString()
            val (code, text) = httpJson(
                GRAPHQL, "POST", body,
                mapOf("accept" to "application/json", "origin" to SITE, "referer" to collectionUrl(slug), "x-app-id" to APP_ID),
            )
            if (code == 429) throw OpenSeaException("OpenSea rate limit (429)")
            if (code !in 200..299) throw OpenSeaException("OpenSea HTTP $code")
            parseGraphql(text)
        }

    private fun parseGraphql(text: String): JSONObject {
        val env = JSONObject(text)
        if (env.has("errors")) {
            val errors = env.getJSONArray("errors")
            if (errors.length() > 0) {
                val msg = errors.getJSONObject(0).optString("message", "GraphQL error")
                throw OpenSeaException("OpenSea: $msg")
            }
        }
        if (env.isNull("data")) throw OpenSeaException("OpenSea mengembalikan data kosong")
        return env.getJSONObject("data")
    }

    private fun parseStage(o: JSONObject): com.osnmz.android.data.StageInfo {
        val type = when (o.optString("stageType")) {
            "PUBLIC_SALE" -> com.osnmz.android.data.StageType.PUBLIC_SALE
            "SIGNED_PRESALE" -> com.osnmz.android.data.StageType.SIGNED_PRESALE
            "MERKLE_PRESALE" -> com.osnmz.android.data.StageType.MERKLE_PRESALE
            else -> com.osnmz.android.data.StageType.UNKNOWN
        }
        var priceWei: BigInteger? = null
        var priceChain: String? = null
        val price = if (o.isNull("price")) null else o.optJSONObject("price")
        val token = price?.optJSONObject("token")
        if (token != null) {
            val contract = token.optString("contractAddress", "")
            // osnm-z only treats a zero-address token as a native price.
            if (contract.equals("0x0000000000000000000000000000000000000000", ignoreCase = true)) {
                val unit = token.opt("unit")
                val dec = when (unit) {
                    is Number -> BigDecimal(unit.toString())
                    is String -> BigDecimal(unit)
                    else -> null
                }
                if (dec != null) {
                    priceWei = dec.multiply(BigDecimal("1000000000000000000")).toBigIntegerExact()
                    priceChain = token.optJSONObject("chain")?.optString("identifier")
                }
            }
        }
        return com.osnmz.android.data.StageInfo(
            index = o.optInt("stageIndex"),
            label = if (o.isNull("label")) null else o.optString("label"),
            type = type,
            startMs = parseTime(o.optString("startTime", "")),
            endMs = parseTime(o.optString("endTime", "")),
            maxPerWallet = if (o.isNull("maxTotalMintableByWallet")) null else o.optLong("maxTotalMintableByWallet"),
            nativePriceWei = priceWei,
            priceChain = priceChain,
            isEligible = null,
            uuid = if (o.isNull("uuid")) null else o.optString("uuid"),
        )
    }

    fun parseTime(iso: String?): Long? {
        if (iso.isNullOrEmpty()) return null
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
    }

    suspend fun collectionMetadata(slug: String): CollectionMeta {
        val query = """
            query MintCollectionMetadata(${'$'}slug: String!) {
              collectionBySlug(slug: ${'$'}slug) {
                __typename
                ... on Collection { slug address chain { identifier networkId } }
              }
              dropBySlug(slug: ${'$'}slug) {
                __typename
                disabledReason
                identifier { contractAddress chain { identifier } }
                ... on Erc721SeaDropV1 { maxSupply totalSupply }
                stages {
                  __typename uuid label stageType stageIndex startTime endTime
                  maxTotalMintableByWallet
                  price { token { unit symbol contractAddress chain { identifier } } }
                }
              }
            }
        """.trimIndent()
        val data = graphqlPost("MintCollectionMetadata", query, JSONObject().put("slug", slug), slug)
        val coll = data.optJSONObject("collectionBySlug")
            ?: throw OpenSeaException("Koleksi tidak ditemukan")
        if (coll.optString("__typename") != "Collection") throw OpenSeaException("Koleksi tidak ditemukan")
        val drop = data.optJSONObject("dropBySlug")
            ?: throw OpenSeaException("Koleksi ini bukan SeaDrop OpenSea")
        if (drop.optString("__typename") != "Erc721SeaDropV1") throw OpenSeaException("Koleksi ini bukan Erc721SeaDropV1")
        val chain = coll.getJSONObject("chain")
        val stages = mutableListOf<com.osnmz.android.data.StageInfo>()
        val arr = drop.optJSONArray("stages") ?: JSONArray()
        for (i in 0 until arr.length()) stages.add(parseStage(arr.getJSONObject(i)))
        return CollectionMeta(
            slug = coll.getString("slug"),
            address = Abi.checksum(coll.getString("address")),
            chainIdentifier = chain.getString("identifier"),
            networkId = chain.opt("networkId").let {
                when (it) { is Number -> it.toLong(); is String -> it.toLong(); else -> throw OpenSeaException("networkId tak valid") }
            },
            dropAddress = Abi.checksum(drop.getJSONObject("identifier").getString("contractAddress")),
            stages = stages,
        )
    }

    suspend fun resolveCollection(locator: String, expectedChainId: Long): CollectionMeta {
        val v = locator.trim()
        val slugRegex = Regex("[A-Za-z0-9_-]{1,200}")
        if (Abi.isAddress(v)) {
            val query = """
                query MintCollectionSearch(${'$'}query: String!) {
                  collectionsByQuery(query: ${'$'}query, limit: 50) {
                    __typename slug address chain { identifier networkId }
                  }
                }
            """.trimIndent()
            val data = graphqlPost("MintCollectionSearch", query, JSONObject().put("query", Abi.checksum(v)), "search")
            val arr = data.optJSONArray("collectionsByQuery") ?: JSONArray()
            val slugs = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optString("__typename") != "Collection") continue
                if (!r.optString("address").equals(v, ignoreCase = true)) continue
                val nid = r.getJSONObject("chain").opt("networkId")
                val nidLong = when (nid) { is Number -> nid.toLong(); is String -> nid.toLong(); else -> -1L }
                if (nidLong != expectedChainId) continue
                slugs.add(r.getString("slug"))
            }
            if (slugs.size > 1) throw OpenSeaException("Kontrak cocok dengan lebih dari satu koleksi di chain ini")
            val slug = slugs.firstOrNull() ?: throw OpenSeaException("Koleksi tidak ditemukan untuk kontrak ini")
            return collectionMetadata(slug)
        }
        val slug = if (slugRegex.matches(v)) v else {
            // https://opensea.io/collection/<slug>/...
            val m = Regex("^https://opensea\\.io/collection/([A-Za-z0-9_-]+)").find(v)
                ?: throw OpenSeaException("Locator tidak valid: pakai slug, URL OpenSea, atau alamat kontrak")
            m.groupValues[1]
        }
        val meta = collectionMetadata(slug)
        if (meta.networkId != expectedChainId) throw OpenSeaException("Koleksi dan RPC berada di chain berbeda")
        return meta
    }

    /** SIWE sign-in. The key is used only to produce this one local signature. */
    suspend fun authenticate(creds: WalletCreds, chainId: Long, slug: String) {
        withContext(Dispatchers.IO) {
            val address = Abi.checksum(creds.address)
            val collUrl = collectionUrl(slug)
            val hint = HttpCookie("connected-account-server-hint", address).apply { domain = ".opensea.io"; path = "/" }
            cookieManager.cookieStore.add(URI(SITE), hint)
            // BUG FIX (was: GET -> HTTP 405): the CLI's _send() defaults to
            // POST, and _request_nonce() relies on that default — it sends
            // a POST with no body to /__api/auth/siwe/nonce. The Android
            // port sent GET, which OpenSea rejects with 405, so eligibility
            // could never be checked. Now it matches the CLI: POST, no body.
            val (nonceCode, nonceText) = httpJson(
                "$SITE/__api/auth/siwe/nonce", "POST", null,
                mapOf("origin" to SITE, "referer" to collUrl),
            )
            if (nonceCode !in 200..299) throw OpenSeaException("SIWE nonce HTTP $nonceCode")
            val nonce = JSONObject(nonceText).optString("nonce", "")
            if (nonce.length !in 8..256 || !nonce.all { it.isLetterOrDigit() }) {
                throw OpenSeaException("OpenSea mengembalikan nonce tak valid")
            }
            val issuedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val message = "opensea.io wants you to sign in with your Ethereum account:\n$address\n\n" +
                "$SIWE_STATEMENT\n\nURI: $collUrl\nVersion: 1\nChain ID: $chainId\nNonce: $nonce\nIssued At: $issuedAt"
            val sigHex = Crypto.signPersonalMessage(message.toByteArray(Charsets.UTF_8), creds.privateKey)
            val body = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("domain", "opensea.io"); put("address", address); put("statement", SIWE_STATEMENT)
                    put("uri", collUrl); put("version", "1"); put("chainId", chainId.toString())
                    put("nonce", nonce); put("issuedAt", issuedAt); put("accountType", "Ethereum")
                })
                put("signature", sigHex); put("chainArch", "EVM")
            }.toString()
            val (verifyCode, verifyText) = httpJson(
                "$SITE/__api/auth/siwe/verify", "POST", body,
                mapOf("origin" to SITE, "referer" to collUrl),
            )
            if (verifyCode !in 200..299) throw OpenSeaException("SIWE verify HTTP $verifyCode")
            val userAddr = JSONObject(verifyText).optJSONObject("user")?.optString("address", "")
            if (!userAddr.equals(address, ignoreCase = true)) {
                throw OpenSeaException("SIWE: alamat terverifikasi tidak cocok dengan wallet")
            }
            authenticatedAddress = address
        }
    }

    suspend fun configChangeHashes(slug: String): List<String> {
        val query = """
            query DropConfigChangesQuery(${'$'}collectionSlug: String!, ${'$'}limit: Int!, ${'$'}after: Cursor) {
              dropBySlug(slug: ${'$'}collectionSlug) {
                configChangeHistory(limit: ${'$'}limit, after: ${'$'}after) { items { timestamp transactionHash } }
              }
            }
        """.trimIndent()
        val vars = JSONObject().put("collectionSlug", slug).put("limit", 20).put("after", JSONObject.NULL)
        val data = graphqlPost("DropConfigChangesQuery", query, vars, slug)
        val items = data.optJSONObject("dropBySlug")?.optJSONObject("configChangeHistory")?.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val h = items.getJSONObject(i).optString("transactionHash", "")
            if (h.matches(Regex("0x[0-9a-fA-F]{64}"))) out.add(h.lowercase())
        }
        return out.distinct()
    }

    suspend fun mintAction(meta: CollectionMeta, wallet: String, quantity: Int): MintAction {
        if (authenticatedAddress == null || !authenticatedAddress.equals(wallet, ignoreCase = true)) {
            throw OpenSeaException("Perlu sign-in OpenSea dulu untuk mint private")
        }
        val query = """
            query MintActionTimelineQuery(${'$'}address: Address!, ${'$'}fromAssets: [AssetQuantityInput!]!, ${'$'}toAssets: [AssetQuantityInput!]!, ${'$'}recipient: Address) {
              swap(address: ${'$'}address, fromAssets: ${'$'}fromAssets, toAssets: ${'$'}toAssets, recipient: ${'$'}recipient, action: MINT) {
                actions {
                  __typename
                  ... on TransactionAction { transactionSubmissionData { to data value } }
                }
                errors { __typename }
              }
            }
        """.trimIndent()
        val vars = JSONObject().apply {
            put("address", Abi.checksum(wallet))
            put("fromAssets", JSONArray().put(JSONObject().put("asset", JSONObject().apply {
                put("contractAddress", "0x0000000000000000000000000000000000000000")
                put("chain", meta.chainIdentifier)
            })))
            put("toAssets", JSONArray().put(JSONObject().apply {
                put("asset", JSONObject().apply {
                    put("contractAddress", meta.dropAddress); put("chain", meta.chainIdentifier); put("tokenId", "0")
                })
                put("quantity", quantity.toString())
            }))
            put("recipient", JSONObject.NULL)
        }
        val data = graphqlPost("MintActionTimelineQuery", query, vars, meta.slug)
        val swap = data.optJSONObject("swap") ?: throw OpenSeaException("OpenSea mengembalikan swap kosong")
        val errors = swap.optJSONArray("errors") ?: JSONArray()
        if (errors.length() > 0) {
            throw OpenSeaException("OpenSea menolak mint action: ${errors.getJSONObject(0).optString("__typename")}")
        }
        val actions = swap.optJSONArray("actions") ?: JSONArray()
        var found: MintAction? = null
        for (i in 0 until actions.length()) {
            val tx = actions.getJSONObject(i).optJSONObject("transactionSubmissionData") ?: continue
            val valueStr = if (tx.isNull("value")) "0" else tx.optString("value", "0")
            val value = if (valueStr.startsWith("0x")) BigInteger(valueStr.removePrefix("0x"), 16) else BigInteger(valueStr)
            found = MintAction(tx.getString("to"), value, Abi.fromHex(tx.getString("data")))
        }
        return found ?: throw OpenSeaException("OpenSea tidak mengembalikan transaksi mint")
    }

    /**
     * Merge an eligibility snapshot into the metadata stages, mirroring
     * osnm-z phase_selection.build_phase_options(). Returns stages with
     * eligibilityLabel / isSelectable / mintedQuantity filled in.
     * Throws nothing: callers fall back to public-only labels on failure.
     */
    fun enrichWithEligibility(
        meta: CollectionMeta,
        wallet: String,
        eligibilityData: JSONObject,
    ): List<com.osnmz.android.data.StageInfo> {
        val drop = eligibilityData.optJSONObject("dropBySlug") ?: return publicOnlyStages(meta)
        val mintedTop = if (drop.isNull("minterQuantityMinted")) null else drop.optLong("minterQuantityMinted")
        // Wallet-specific counters, keyed by stage uuid (CLI: _decode_account_wallet).
        var minted: Long? = mintedTop
        val walletStages = mutableMapOf<String, JSONObject>()
        val account = drop.optJSONObject("accountStageEligibility")
        val wallets = account?.optJSONArray("wallets") ?: JSONArray()
        for (i in 0 until wallets.length()) {
            val w = wallets.getJSONObject(i)
            if (!w.optString("address", "").equals(wallet, ignoreCase = true)) continue
            if (!w.isNull("quantityMinted")) minted = w.optLong("quantityMinted")
            val arr = w.optJSONArray("stages") ?: JSONArray()
            for (j in 0 until arr.length()) {
                val s = arr.getJSONObject(j)
                val uuid = s.optString("dropStageUuid", "")
                if (uuid.isNotEmpty()) walletStages[uuid] = s
            }
        }
        val eligStages = drop.optJSONArray("stages") ?: JSONArray()
        val byIndex = mutableMapOf<Int, JSONObject>()
        val byUuid = mutableMapOf<String, JSONObject>()
        for (i in 0 until eligStages.length()) {
            val s = eligStages.getJSONObject(i)
            byIndex[s.optInt("stageIndex")] = s
            val uuid = s.optString("uuid", "")
            if (uuid.isNotEmpty()) byUuid[uuid] = s
        }
        val now = System.currentTimeMillis()
        return meta.stages.map { st ->
            val elig = (st.uuid?.let { byUuid[it] }) ?: byIndex[st.index]
            if (elig == null) return@map st.copy(eligibilityLabel = "ineligible", isEligible = false, isSelectable = false, mintedQuantity = minted)
            val walletStage = st.uuid?.let { walletStages[it] }
            val isEligibleRaw: Boolean? = when {
                walletStage != null && !walletStage.isNull("isEligible") -> walletStage.optBoolean("isEligible")
                !elig.isNull("isEligible") -> elig.optBoolean("isEligible")
                else -> null
            }
            val linkedWallet = elig.optString("eligibleMinterAddress", "").takeIf { it.isNotEmpty() && !it.equals(wallet, ignoreCase = true) }
            val limit = when {
                walletStage != null && !walletStage.isNull("maxTotalMintableByWallet") -> walletStage.optLong("maxTotalMintableByWallet")
                !elig.isNull("eligibleMaxTotalMintableByWallet") -> elig.optLong("eligibleMaxTotalMintableByWallet")
                !elig.isNull("maxTotalMintableByWallet") -> elig.optLong("maxTotalMintableByWallet")
                else -> st.maxPerWallet
            }
            val isEligible = if (st.type == com.osnmz.android.data.StageType.PUBLIC_SALE) true else (isEligibleRaw == true && linkedWallet == null)
            val available: Long? = when {
                !isEligible -> 0L
                limit == null || minted == null -> limit
                else -> maxOf(0L, limit - minted)
            }
            val limitReached = isEligible && limit != null && minted != null && available == 0L
            val label = when {
                st.type == com.osnmz.android.data.StageType.PUBLIC_SALE -> "eligible (public)"
                limitReached -> "OpenSea limit reached ($minted/$limit)"
                linkedWallet != null -> "requires eligible linked wallet"
                isEligible -> "eligible"
                else -> "ineligible"
            }
            val ended = st.endMs != null && now >= st.endMs!!
            val selectable = isEligible && (available == null || available > 0) && (st.type == com.osnmz.android.data.StageType.PUBLIC_SALE || !ended)
            st.copy(
                isEligible = isEligible,
                eligibilityLabel = label,
                isSelectable = selectable,
                maxPerWallet = limit ?: st.maxPerWallet,
                mintedQuantity = minted,
            )
        }
    }

    /** CLI fallback (public_only_eligibility): only PUBLIC is eligible. */
    fun publicOnlyStages(meta: CollectionMeta): List<com.osnmz.android.data.StageInfo> {
        return meta.stages.map { st ->
            val isEligible = st.type == com.osnmz.android.data.StageType.PUBLIC_SALE
            st.copy(
                isEligible = isEligible,
                eligibilityLabel = if (isEligible) "eligible (public)" else "ineligible",
                isSelectable = isEligible,
                mintedQuantity = null,
            )
        }
    }

    /** Eligibility exactly like the CLI first: GET with the persisted
     *  query (app_id + operationName + variables + extensions.sha256Hash),
     *  falling back to a full POST only if that GET fails. The Android
     *  port previously went straight to POST, which is not what the CLI
     *  does and may be rejected for this query. */
    suspend fun eligibility(slug: String, wallet: String): JSONObject {
        val vars = JSONObject().put("address", wallet.lowercase()).put("collectionSlug", slug)
        try {
            return graphqlGetEligibility(vars, slug)
        } catch (e: Exception) {
            // Fall through to the POST form below; if that also fails, its
            // exception is the one the caller sees.
            return eligibilityPost(slug, wallet)
        }
    }

    private suspend fun graphqlGetEligibility(variables: JSONObject, slug: String): JSONObject =
        withContext(Dispatchers.IO) {
            val extensions = JSONObject().put("persistedQuery", JSONObject().apply {
                put("sha256Hash", ELIGIBILITY_HASH); put("version", 1)
            })
            val params = "app_id=" + java.net.URLEncoder.encode(APP_ID, "UTF-8") +
                "&operationName=" + java.net.URLEncoder.encode("DropEligibilityQuery", "UTF-8") +
                "&variables=" + java.net.URLEncoder.encode(variables.toString(), "UTF-8") +
                "&extensions=" + java.net.URLEncoder.encode(extensions.toString(), "UTF-8")
            val (code, text) = httpJson(
                "$GRAPHQL?$params", "GET", null,
                mapOf("accept" to "application/json", "origin" to SITE, "referer" to collectionUrl(slug)),
            )
            if (code == 429) throw OpenSeaException("OpenSea rate limit (429)")
            if (code !in 200..299) throw OpenSeaException("OpenSea HTTP $code (eligibility GET)")
            parseGraphql(text)
        }

    private suspend fun eligibilityPost(slug: String, wallet: String): JSONObject {
        val query = """
            query DropEligibilityQuery(${'$'}collectionSlug: String!, ${'$'}address: Address!) {
              dropBySlug(slug: ${'$'}collectionSlug) {
                __typename
                ... on Erc721SeaDropV1 { minterQuantityMinted(minter: ${'$'}address) }
                accountStageEligibility {
                  wallets { address quantityMinted stages { dropStageUuid isEligible maxTotalMintableByWallet price { token { unit symbol contractAddress chain { identifier } } } } }
                }
                stages {
                  __typename uuid stageType stageIndex isEligible eligibleMinterAddress
                  maxTotalMintableByWallet eligibleMaxTotalMintableByWallet
                  eligiblePrice { token { unit symbol contractAddress chain { identifier } } }
                }
              }
            }
        """.trimIndent()
        val vars = JSONObject().put("address", wallet.lowercase()).put("collectionSlug", slug)
        return graphqlPost("DropEligibilityQuery", query, vars, slug)
    }
}

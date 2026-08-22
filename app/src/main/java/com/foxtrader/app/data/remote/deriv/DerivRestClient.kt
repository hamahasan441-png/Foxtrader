package com.foxtrader.app.data.remote.deriv

import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.di.DerivApiClient
import com.foxtrader.app.domain.model.deriv.DerivAccount
import com.foxtrader.app.domain.model.deriv.DerivAccountType
import com.foxtrader.app.domain.model.deriv.DerivOtpSession
import com.foxtrader.app.domain.model.deriv.DerivWallet
import com.foxtrader.app.domain.model.deriv.DerivWalletBalance
import com.foxtrader.app.domain.model.deriv.DerivWalletTransaction
import com.foxtrader.app.domain.model.deriv.DerivWalletTransactionsPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DerivRestClient @Inject constructor(
    @DerivApiClient private val client: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun health(): Boolean = withContext(io) {
        client.newCall(
            Request.Builder().url("$BASE_URL/v1/health").get().build()
        ).execute().use { response ->
            response.isSuccessful && response.body?.string()?.trim() == "OK"
        }
    }

    suspend fun createDemoAccount(appId: String, token: String, currency: String = "USD", group: String = "row"): DerivAccount = withContext(io) {
        require(currency == "USD") { "Current Deriv Options account schema supports USD demo accounts only" }
        require(group == "row") { "Current Deriv Options account schema supports the row group only" }
        val body = """{"currency":"${currency}","group":"${group}","account_type":"demo"}"""
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val root = executeJson(
            Request.Builder()
                .url("$BASE_URL/trading/v1/options/accounts")
                .header("Deriv-App-ID", appId.trim())
                .header("Authorization", "Bearer ${token.trim()}")
                .post(body)
                .build(),
        )
        val data = root["data"] ?: throw DerivApiException("Missing created account data")
        val obj = when (data) {
            is JsonObject -> data
            is JsonArray -> data.firstOrNull() as? JsonObject
            else -> null
        } ?: throw DerivApiException("Invalid created account response")
        parseAccount(obj) ?: throw DerivApiException("Created account is missing its ID")
    }

    suspend fun resetDemoBalance(appId: String, token: String, account: DerivAccount) = withContext(io) {
        require(account.accountType == DerivAccountType.DEMO) { "Only demo accounts can be reset" }
        require(account.accountId.matches(ACCOUNT_ID_REGEX)) { "Invalid Deriv account ID" }
        val request = Request.Builder()
            .url("$BASE_URL/trading/v1/options/accounts/${account.accountId}/reset-demo-balance")
            .header("Deriv-App-ID", appId.trim())
            .header("Authorization", "Bearer ${token.trim()}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        executeNoContent(request)
    }

    suspend fun getAccounts(appId: String, token: String): List<DerivAccount> = withContext(io) {
        val root = executeJson(
            Request.Builder()
                .url("$BASE_URL/trading/v1/options/accounts")
                .header("Deriv-App-ID", appId.trim())
                .header("Authorization", "Bearer ${token.trim()}")
                .get()
                .build(),
        )
        val data = root["data"] ?: throw DerivApiException("Missing account data")
        val items = when (data) {
            is JsonArray -> data
            is JsonObject -> JsonArray(listOf(data))
            else -> JsonArray(emptyList())
        }
        items.mapNotNull { element -> parseAccount(element as? JsonObject ?: return@mapNotNull null) }
    }

    suspend fun wallets(appId: String, token: String, conversionCurrency: String? = "USD"): List<DerivWallet> = withContext(io) {
        val currency = conversionCurrency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        require(currency == null || currency.matches(ISO_CURRENCY_REGEX)) { "Conversion currency must be a 3-letter ISO code" }
        val url = "$BASE_URL/wallet/v1/wallets".toHttpUrl().newBuilder().apply {
            currency?.let { addQueryParameter("conversion_currency", it) }
        }.build()
        val root = executeJson(
            Request.Builder()
                .url(url)
                .header("Deriv-App-ID", appId.trim())
                .header("Authorization", "Bearer ${token.trim()}")
                .get()
                .build(),
        )
        val data = root["data"] as? JsonArray ?: throw DerivApiException("Missing wallet data")
        data.mapNotNull walletLoop@ { element ->
            val obj = element as? JsonObject ?: return@walletLoop null
            val walletId = obj.string("wallet_id") ?: return@walletLoop null
            val walletType = obj.string("type") ?: return@walletLoop null
            val balancesObj = obj["balances"] as? JsonObject ?: JsonObject(emptyMap())
            val balances = balancesObj.entries.mapNotNull balanceLoop@ { (code, value) ->
                val balanceObj = value as? JsonObject ?: return@balanceLoop null
                val balance = balanceObj.string("balance") ?: return@balanceLoop null
                val input = balanceObj.string("input") ?: return@balanceLoop null
                val output = balanceObj.string("output") ?: return@balanceLoop null
                DerivWalletBalance(code, balance, input, output)
            }.sortedBy { it.currency }
            val total = obj["total_balance"] as? JsonObject
            DerivWallet(
                walletId = walletId,
                type = walletType,
                balances = balances,
                convertedTo = total?.string("converted_to"),
                approximateTotalBalance = total?.string("approximate_total_balance"),
            )
        }
    }

    suspend fun walletTransactions(
        appId: String,
        token: String,
        walletType: String,
        perPage: Int = 100,
    ): DerivWalletTransactionsPage = withContext(io) {
        val normalizedType = walletType.trim().lowercase()
        require(normalizedType in WALLET_TYPES) { "Unsupported wallet type" }
        val url = "$BASE_URL/wallet/v1/transactions/$normalizedType".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", perPage.coerceIn(100, 1000).toString())
            .build()
        executeWalletTransactionsPage(appId, token, url.toString())
    }

    suspend fun walletTransactionsPage(
        appId: String,
        token: String,
        pageUrl: String,
    ): DerivWalletTransactionsPage = withContext(io) {
        val safeUrl = normalizeWalletPageUrl(pageUrl)
            ?: throw DerivApiException("Rejected unsafe wallet pagination URL")
        executeWalletTransactionsPage(appId, token, safeUrl)
    }

    private fun executeWalletTransactionsPage(
        appId: String,
        token: String,
        url: String,
    ): DerivWalletTransactionsPage {
        val root = executeJson(
            Request.Builder()
                .url(url)
                .header("Deriv-App-ID", appId.trim())
                .header("Authorization", "Bearer ${token.trim()}")
                .get()
                .build(),
        )
        val data = root["data"] as? JsonObject ?: throw DerivApiException("Missing wallet transaction data")
        val items = data["transactions"] as? JsonArray ?: JsonArray(emptyList())
        val transactions = items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val metadata = obj["metadata"] as? JsonObject ?: return@mapNotNull null
            DerivWalletTransaction(
                requestId = obj.string("request_id") ?: return@mapNotNull null,
                transactionId = obj.long("transaction_id") ?: return@mapNotNull null,
                timestamp = obj.string("timestamp") ?: return@mapNotNull null,
                category = obj.string("category") ?: return@mapNotNull null,
                channel = obj.string("channel") ?: return@mapNotNull null,
                status = metadata.string("transaction_status") ?: return@mapNotNull null,
                grossAmount = metadata.string("transaction_gross_amount") ?: return@mapNotNull null,
                netAmount = metadata.string("transaction_net_amount") ?: return@mapNotNull null,
                currency = metadata.string("transaction_currency") ?: return@mapNotNull null,
            )
        }
        val links = root["links"] as? JsonObject
        return DerivWalletTransactionsPage(
            transactions = transactions,
            nextPageUrl = links?.string("next")?.let(::normalizeWalletPageUrl),
            previousPageUrl = links?.string("prev")?.let(::normalizeWalletPageUrl),
        )
    }

    suspend fun requestOtp(appId: String, token: String, account: DerivAccount): DerivOtpSession = withContext(io) {
        require(account.accountId.matches(ACCOUNT_ID_REGEX)) { "Invalid Deriv account ID" }
        val request = Request.Builder()
            .url("$BASE_URL/trading/v1/options/accounts/${account.accountId}/otp")
            .header("Deriv-App-ID", appId.trim())
            .header("Authorization", "Bearer ${token.trim()}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val root = executeJson(request)
        val data = root["data"]?.jsonObject ?: throw DerivApiException("Missing OTP response")
        val url = data["url"]?.jsonPrimitive?.contentOrNull
            ?: throw DerivApiException("Missing WebSocket URL")
        if (!isExpectedWebSocketUrl(url)) {
            throw DerivApiException("Unexpected Deriv WebSocket URL")
        }
        DerivOtpSession(url, account.accountId, account.accountType)
    }


    private fun executeNoContent(request: Request) {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) return
            val raw = response.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (root != null) throw apiError(root, "Deriv HTTP ${response.code}")
            throw DerivApiException("Deriv HTTP ${response.code}")
        }
    }

    private fun executeJson(request: Request): JsonObject {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }
                .getOrElse { throw DerivApiException("Invalid Deriv response", it) }
            if (!response.isSuccessful) throw apiError(root, "Deriv HTTP ${response.code}")
            val errors = root["errors"] as? JsonArray
            if (!errors.isNullOrEmpty()) throw apiError(root, "Deriv request failed")
            return root
        }
    }

    private fun apiError(root: JsonObject, fallback: String): DerivApiException {
        val first = (root["errors"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }
        val code = first?.get("code")?.jsonPrimitive?.contentOrNull
        val message = first?.get("message")?.jsonPrimitive?.contentOrNull
        return DerivApiException(listOfNotNull(code, message).joinToString(": ").ifBlank { fallback })
    }

    private fun parseAccount(obj: JsonObject): DerivAccount? {
        val id = obj.string("account_id") ?: obj.string("id") ?: return null
        val typeRaw = obj.string("account_type")?.lowercase()
        return DerivAccount(
            accountId = id,
            accountType = when (typeRaw) {
                "demo" -> DerivAccountType.DEMO
                "real" -> DerivAccountType.REAL
                else -> DerivAccountType.UNKNOWN
            },
            currency = obj.string("currency") ?: "USD",
            balance = obj.double("balance"),
            group = obj.string("group"),
            status = obj.string("status"),
        )
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun isExpectedWebSocketUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("wss", ignoreCase = true) &&
            uri.host.equals("api.derivws.com", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.path.startsWith("/trading/v1/options/ws/")
    }.getOrDefault(false)

    private fun normalizeWalletPageUrl(url: String): String? = runCatching {
        val uri = URI(url.trim())
        val safePath = uri.path?.startsWith("/wallet/v1/transactions/") == true
        if (!safePath || uri.userInfo != null || uri.fragment != null) return@runCatching null

        if (uri.isAbsolute) {
            val safeOrigin = uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("api.derivws.com", ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443)
            if (!safeOrigin) return@runCatching null
            uri.toString()
        } else {
            if (uri.host != null || !uri.path.startsWith("/")) return@runCatching null
            BASE_URL + uri.toString()
        }
    }.getOrNull()

    private companion object {
        const val BASE_URL = "https://api.derivws.com"
        val ACCOUNT_ID_REGEX = Regex("^[A-Za-z0-9_-]{3,64}$")
        val ISO_CURRENCY_REGEX = Regex("^[A-Z]{3}$")
        val WALLET_TYPES = setOf("main", "p2p", "partner", "payment_agent")
    }
}

class DerivApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

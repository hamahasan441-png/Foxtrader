package com.foxtrader.app.domain.model

/**
 * Domain models for MT4 account integration via MetaApi.
 */

/**
 * Credentials required to connect an MT4 account through MetaApi.
 *
 * @param login The MT4 account login number.
 * @param password The MT4 account password (investor or master).
 * @param server The MT4 broker server name (e.g. "ICMarkets-Demo").
 * @param platform Trading platform identifier (defaults to "mt4").
 */
data class Mt4Credentials(
    val login: Long,
    val password: String,
    val server: String,
    val platform: String = "mt4",
)


/**
 * Password-free saved broker account descriptor for Phase 6 account switching.
 * Credentials are never persisted here; selecting a profile only pre-fills
 * login/server/platform and still requires the user to enter the password.
 */
data class Mt4AccountProfile(
    val login: Long,
    val server: String,
    val platform: String = "mt4",
    val displayName: String = "",
    /** Non-secret MetaApi provisioning id used to avoid duplicate cloud accounts. */
    val metaApiAccountId: String? = null,
)

/**
 * MT4/MT5 account information retrieved from the broker via MetaApi.
 */
data class Mt4AccountInfo(
    val login: Long,
    val balance: Double,
    val equity: Double,
    val margin: Double,
    val freeMargin: Double,
    val leverage: Int,
    val currency: String,
    val name: String,
    val server: String,
)

/**
 * An open position (or pending order) on the MT4 account.
 */
data class Mt4Position(
    val ticket: Long,
    val symbol: String,
    val type: Mt4OrderType,
    val lots: Double,
    val openPrice: Double,
    val openTime: Long,
    val sl: Double,
    val tp: Double,
    val profit: Double,
    val swap: Double,
    val commission: Double,
)

/**
 * MT4 order types.
 */
enum class Mt4OrderType {
    BUY,
    SELL,
    BUY_LIMIT,
    SELL_LIMIT,
    BUY_STOP,
    SELL_STOP,
}

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
    val login: Int,
    val password: String,
    val server: String,
    val platform: String = "mt4",
)

/**
 * MT4 account information retrieved from the broker via MetaApi.
 */
data class Mt4AccountInfo(
    val login: Int,
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

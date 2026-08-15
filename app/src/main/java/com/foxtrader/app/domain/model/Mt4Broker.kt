package com.foxtrader.app.domain.model

/**
 * A known MT4 broker and the server name(s) used to connect via MetaApi.
 *
 * MT4 connections require an exact `server` string (e.g. `ICMarkets-Demo`).
 * Typing that from memory is error-prone, so the app ships a searchable
 * directory of well-known brokers ([com.foxtrader.app.domain.usecase.mt4.Mt4BrokerDirectory])
 * to auto-fill the login form's server field.
 */
data class Mt4Broker(
    /** Display name, e.g. "IC Markets". */
    val name: String,
    /** Broker MT4 server string(s). Usually one per account type (Live/Demo). */
    val servers: List<String>,
    /** Short description shown in search results. */
    val description: String = "",
    /** Primary country/region of regulation, for display. */
    val country: String = "",
    /** True if the listed servers are demo servers. */
    val demo: Boolean = false,
) {
    /** True if [query] matches this broker by name or any server. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        val lower = q.lowercase()
        return name.lowercase().contains(lower) ||
            servers.any { it.lowercase().contains(lower) }
    }
}

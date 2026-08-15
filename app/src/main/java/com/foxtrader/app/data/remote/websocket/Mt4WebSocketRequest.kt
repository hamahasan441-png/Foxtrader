package com.foxtrader.app.data.remote.websocket

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Builds the MetaApi streaming WebSocket [Request] and provides a fully
 * redacted diagnostic form.
 *
 * MetaApi's streaming contract authenticates via the `auth-token` query
 * parameter on the WebSocket URL (header-based auth is not supported for the
 * upgrade). That means the raw URL carries a live credential, so it must never
 * be logged or included in crash reports. [redacted] returns a copy of the URL
 * in which the token value is replaced with a fixed placeholder.
 */
class Mt4WebSocketRequest private constructor(
    /** The real request sent over the wire. Never log or serialize this. */
    val request: Request,
    private val redactedUrl: String,
) {

    /** Safe-for-diagnostics representation; the token value is removed. */
    fun redacted(): String = redactedUrl

    companion object {
        internal const val BASE_WS_URL = "wss://mt-client-api-v1.agiliumtrade.agiliumtrade.ai/ws"
        internal const val AUTH_TOKEN_QUERY = "auth-token"
        internal const val REDACTED = "[REDACTED]"

        /**
         * Creates a [Mt4WebSocketRequest], or `null` when the token/account are
         * blank (nothing meaningful to connect with) or the composed URL is
         * malformed. A null result means the caller should not attempt a
         * connection at all.
         */
        fun create(authToken: String, accountId: String): Mt4WebSocketRequest? {
            if (authToken.isBlank() || accountId.isBlank()) return null

            val url = "$BASE_WS_URL?$AUTH_TOKEN_QUERY=$authToken&accountId=$accountId"
                .toHttpUrlOrNull() ?: return null

            val request = Request.Builder().url(url).build()

            // Build the safe diagnostic URL: keep the account id (not secret)
            // but replace the token value so it can never leak via logs.
            val redactedUrl = request.url.newBuilder()
                .removeAllQueryParameters(AUTH_TOKEN_QUERY)
                .setQueryParameter(AUTH_TOKEN_QUERY, REDACTED)
                .build()
                .toString()

            return Mt4WebSocketRequest(request, redactedUrl)
        }
    }
}

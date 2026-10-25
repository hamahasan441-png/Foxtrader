package com.foxtrader.app.data.remote.dukascopy

/**
 * Dukascopy's live candle host (`jetta.dukascopy.com`) answers non-browser
 * clients with an empty HTTP 202 and `x-amzn-waf-action: challenge`. A real
 * Chromium session solves that challenge and receives an `aws-waf-token`
 * cookie. Later requests must send that cookie with the same user agent.
 *
 * The old `.bi5` host (`datafeed.dukascopy.com`) no longer replies at all
 * (the TLS handshake succeeds and then the socket hangs), and the official
 * replacement is a requester-pays S3 bucket, which a free chart cannot use.
 */
interface DukascopyWafSession {
    /** User agent of the browser that minted [token], or null before that. */
    val userAgent: String?

    /**
     * Current token, acquiring one if needed. Null when no browser session is
     * available or the challenge could not be solved. Must not throw.
     */
    suspend fun token(): String?

    /** Drop [used] if it is still the token we would send. */
    fun invalidate(used: String?)

    companion object {
        val None: DukascopyWafSession = object : DukascopyWafSession {
            override val userAgent: String? = null
            override suspend fun token(): String? = null
            override fun invalidate(used: String?) = Unit
        }
    }
}

/** Pull `aws-waf-token` out of a `Cookie` header. The value itself may contain `=`. */
internal fun extractAwsWafToken(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null
    return cookieHeader.split(';')
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(WAF_COOKIE_PREFIX) }
        ?.removePrefix(WAF_COOKIE_PREFIX)
        ?.takeIf { it.isNotEmpty() }
}

internal const val WAF_COOKIE_PREFIX = "aws-waf-token="
internal const val JETTA_ORIGIN = "https://jetta.dukascopy.com"

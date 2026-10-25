package com.foxtrader.app.data.remote.dukascopy

/**
 * How a Dukascopy HTTP response may be used.
 *
 * The public candle host (`jetta.dukascopy.com`) now answers bot clients with
 * HTTP 202 and an empty body plus `x-amzn-waf-action: challenge`. OkHttp treats
 * 202 as success. Caching that empty body as "the market published no candles"
 * permanently blanks every chart until the process restarts, and it also hides
 * the `.bi5` archive fallback behind a fake empty history.
 */
internal enum class DukascopyHttpOutcome {
    /** HTTP 200 with a body that may be decoded. */
    DATA,

    /** A real 404: this hour/day was not published. Safe to treat as no rows. */
    ABSENT,

    /** WAF challenge, 202/401/403/429/5xx, or a blank 200. Must not be cached. */
    RETRY,
}

internal fun classifyDukascopyResponse(
    code: Int,
    bodyEmpty: Boolean,
    challenged: Boolean,
): DukascopyHttpOutcome = when {
    challenged -> DukascopyHttpOutcome.RETRY
    code == 404 -> DukascopyHttpOutcome.ABSENT
    code == 200 && !bodyEmpty -> DukascopyHttpOutcome.DATA
    else -> DukascopyHttpOutcome.RETRY
}

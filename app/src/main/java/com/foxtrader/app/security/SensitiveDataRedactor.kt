package com.foxtrader.app.security

/** Redacts common credentials before text is allowed into diagnostics/crash breadcrumbs. */
object SensitiveDataRedactor {
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
    private val keyValueSecret = Regex(
        "(?i)(password|passwd|token|access[_-]?token|refresh[_-]?token|api[_-]?key|secret|authorization)\\s*[:=]\\s*([^\\s,;&]+)",
    )
    private val jsonSecret = Regex(
        "(?i)(\\\"(?:password|passwd|token|access_token|refresh_token|api_key|secret|authorization)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")",
    )

    fun redact(input: String): String = input
        .replace(bearer, "Bearer <redacted>")
        .replace(jsonSecret, "$1<redacted>$2")
        .replace(keyValueSecret) { match -> "${match.groupValues[1]}=<redacted>" }
}

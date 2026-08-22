package com.foxtrader.app.domain.usecase.release

import com.foxtrader.app.domain.model.ReadinessCheck
import com.foxtrader.app.domain.model.ReadinessSeverity
import com.foxtrader.app.domain.model.ReleaseReadinessInput
import com.foxtrader.app.domain.model.ReleaseReadinessReport

/**
 * Deterministic, side-effect free production-release gate.
 *
 * BLOCKER means a production binary must not be promoted. WARNING means the
 * binary may be useful for internal QA, but the item should be closed before a
 * public/live-money release.
 */
class ReleaseReadinessEvaluator {
    fun evaluate(input: ReleaseReadinessInput): ReleaseReadinessReport {
        val checks = buildList {
            add(
                check(
                    id = "signing",
                    title = "Release signing",
                    ok = !input.productionBuild || input.releaseSigningConfigured,
                    blocker = input.productionBuild,
                    passDetail = "Production signing material is configured outside the repository.",
                    failDetail = "Production build has no release signing configuration.",
                ),
            )
            add(
                check(
                    id = "cleartext",
                    title = "Cleartext network traffic",
                    ok = !input.cleartextTrafficAllowed,
                    blocker = true,
                    passDetail = "Cleartext traffic is disabled.",
                    failDetail = "HTTP cleartext traffic is enabled.",
                ),
            )
            add(
                check(
                    id = "http_logging",
                    title = "Release HTTP logging",
                    ok = !input.releaseHttpLoggingEnabled,
                    blocker = true,
                    passDetail = "Network logging is disabled for release.",
                    failDetail = "Release HTTP logging could expose account or trading metadata.",
                ),
            )
            add(
                check(
                    id = "credential_storage",
                    title = "Credential storage",
                    ok = input.encryptedCredentialStorage,
                    blocker = true,
                    passDetail = "Secrets use encrypted platform-backed storage.",
                    failDetail = "Credentials are not protected by encrypted storage.",
                ),
            )
            add(
                check(
                    id = "live_automation",
                    title = "Live execution approval",
                    ok = !input.unattendedLiveExecutionPossible,
                    blocker = true,
                    passDetail = "Live orders require an operator-confirmed execution path.",
                    failDetail = "Unattended live-money order submission is possible.",
                ),
            )

            val backendOk = input.backendBaseUrl.startsWith("https://")
            add(
                check(
                    id = "backend_tls",
                    title = "Backend TLS",
                    ok = backendOk,
                    blocker = input.productionBuild,
                    passDetail = "Backend endpoint uses HTTPS.",
                    failDetail = if (input.backendBaseUrl.isBlank()) {
                        "Production build is missing the required FoxTrader backend endpoint."
                    } else {
                        "Configured backend must use HTTPS."
                    },
                ),
            )

            add(
                ReadinessCheck(
                    id = "certificate_pins",
                    title = "Certificate pinning",
                    detail = if (input.certificatePinsConfigured) {
                        "Backend certificate pins are configured."
                    } else {
                        "No backend certificate pins are configured; set production pins when a FoxTrader backend is used."
                    },
                    severity = if (input.certificatePinsConfigured || input.backendBaseUrl.isBlank()) {
                        ReadinessSeverity.PASS
                    } else {
                        ReadinessSeverity.WARNING
                    },
                ),
            )

            add(optionalQaCheck("unit_tests", "Unit tests", input.unitTestsPassed))
            add(optionalQaCheck("lint", "Android lint", input.lintPassed))

            add(
                ReadinessCheck(
                    id = "crash_reporting",
                    title = "Crash reporting",
                    detail = if (input.crashReportingConfigured) {
                        "Remote crash reporting is configured with redacted diagnostics."
                    } else {
                        "Remote crash reporting is disabled; local crash capture remains available."
                    },
                    severity = if (input.crashReportingConfigured) ReadinessSeverity.PASS else ReadinessSeverity.WARNING,
                ),
            )
        }
        return ReleaseReadinessReport(checks)
    }

    private fun check(
        id: String,
        title: String,
        ok: Boolean,
        blocker: Boolean,
        passDetail: String,
        failDetail: String,
        emptyAsWarning: Boolean = false,
    ): ReadinessCheck = ReadinessCheck(
        id = id,
        title = title,
        detail = if (ok) passDetail else failDetail,
        severity = when {
            ok && emptyAsWarning -> ReadinessSeverity.WARNING
            ok -> ReadinessSeverity.PASS
            blocker -> ReadinessSeverity.BLOCKER
            else -> ReadinessSeverity.WARNING
        },
    )

    private fun optionalQaCheck(id: String, title: String, passed: Boolean?): ReadinessCheck = when (passed) {
        true -> ReadinessCheck(id, title, "$title passed.", ReadinessSeverity.PASS)
        false -> ReadinessCheck(id, title, "$title failed.", ReadinessSeverity.BLOCKER)
        null -> ReadinessCheck(id, title, "$title has not been executed in this environment.", ReadinessSeverity.WARNING)
    }
}

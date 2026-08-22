package com.foxtrader.app.domain.model

enum class ReadinessSeverity { PASS, WARNING, BLOCKER }

data class ReadinessCheck(
    val id: String,
    val title: String,
    val detail: String,
    val severity: ReadinessSeverity,
)

data class ReleaseReadinessInput(
    val productionBuild: Boolean,
    val releaseSigningConfigured: Boolean,
    val cleartextTrafficAllowed: Boolean,
    val releaseHttpLoggingEnabled: Boolean,
    val encryptedCredentialStorage: Boolean,
    val unattendedLiveExecutionPossible: Boolean,
    val backendBaseUrl: String,
    val certificatePinsConfigured: Boolean,
    val crashReportingConfigured: Boolean,
    val unitTestsPassed: Boolean?,
    val lintPassed: Boolean?,
)

data class ReleaseReadinessReport(
    val checks: List<ReadinessCheck>,
) {
    val blockerCount: Int = checks.count { it.severity == ReadinessSeverity.BLOCKER }
    val warningCount: Int = checks.count { it.severity == ReadinessSeverity.WARNING }
    val passCount: Int = checks.count { it.severity == ReadinessSeverity.PASS }
    val releasable: Boolean = blockerCount == 0
}

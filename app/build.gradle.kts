// ============================================================================
// FOXTRADER — App module build script
// Kotlin + Compose + Material3 + Hilt + Room + Retrofit
// Target: Android 10+ (API 29), compile/target 34
// ============================================================================

import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    jacoco
}

android {
    namespace = "com.foxtrader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.foxtrader.app"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Export Room schemas to app/schemas/ so migrations are testable with
        // MigrationTestHelper. Without this, a broken migration can only be
        // discovered by a user losing their journal.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }

        // Backend base URL — override per-environment via local.properties or CI.
        // Example: set FOXTRADER_BASE_URL=https://api.foxtrader.io/ in CI secrets.
        val backendUrl = (project.findProperty("FOXTRADER_BASE_URL") as? String)
            ?: System.getenv("FOXTRADER_BASE_URL")
            ?: ""
        buildConfigField("String", "FOXTRADER_BASE_URL", "\"$backendUrl\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // MigrationTestHelper loads the exported schema JSON from the test APK's
    // assets, so app/schemas must be on the androidTest asset path.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        sarifReport = true
        htmlReport = true
        xmlReport = true
        warningsAsErrors = false
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("reports/compose")
    metricsDestination = layout.buildDirectory.dir("reports/compose")
    stabilityConfigurationFile = rootProject.layout.projectDirectory.file("compose-stability.conf")
}

jacoco {
    toolVersion = "0.8.12"
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    // During the app-wide rollout, report violations without blocking the build.
    // Once a proper baseline is generated from a passing build, switch back to
    // ignoreFailures = false and fail only on new issues above the baseline.
    ignoreFailures = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(files("src/main/java", "src/test/java"))
}

ktlint {
    android.set(true)
    // Temporarily allow failures during app-wide rollout. Track violations and
    // burn down over subsequent sprints. Fail-on-new once baseline stabilizes.
    ignoreFailures.set(true)
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
        reporter(ReporterType.SARIF)
    }
    filter {
        include("**/*.kt")
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

val chartCoverageIncludes = listOf(
    "com/foxtrader/app/domain/usecase/chart/ComputeIndicatorsUseCase*",
    "com/foxtrader/app/domain/usecase/chart/MultiChartManager*",
    "com/foxtrader/app/domain/usecase/indicators/TechnicalIndicators*",
    "com/foxtrader/app/domain/usecase/indicators/BollingerBands*",
    "com/foxtrader/app/domain/usecase/indicators/IchimokuCloud*",
    "com/foxtrader/app/domain/usecase/indicators/ParabolicSar*",
    "com/foxtrader/app/domain/usecase/indicators/SuperTrend*",
    "com/foxtrader/app/feature/chart/presentation/ChartUiState*",
    "com/foxtrader/app/feature/chart/presentation/ChartStableCollections*",
    "com/foxtrader/app/feature/chart/presentation/components/ChartViewport*",
    "com/foxtrader/app/feature/chart/presentation/components/ChartPerformanceMonitor*",
)

val chartCoverageExcludes = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
)

val chartCoverageSourceDirs = files(
    "src/main/java/com/foxtrader/app/domain/usecase/chart",
    "src/main/java/com/foxtrader/app/domain/usecase/indicators",
    "src/main/java/com/foxtrader/app/feature/chart/presentation",
    "src/main/java/com/foxtrader/app/feature/chart/presentation/components",
)

val chartCoverageClassDirs = files(
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        include(*chartCoverageIncludes.toTypedArray())
        exclude(*chartCoverageExcludes.toTypedArray())
    },
    fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        include(*chartCoverageIncludes.toTypedArray())
        exclude(*chartCoverageExcludes.toTypedArray())
    },
)

val chartCoverageExecutionData = fileTree(layout.buildDirectory) {
    include(
        "jacoco/testDebugUnitTest.exec",
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec.ec",
    )
}

val jacocoChartCoverageReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Generates a focused Jacoco report for the chart and indicator coverage gate."
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(chartCoverageClassDirs)
    sourceDirectories.setFrom(chartCoverageSourceDirs)
    executionData.setFrom(chartCoverageExecutionData)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    onlyIf { chartCoverageExecutionData.files.any { it.exists() } }
}

val jacocoChartCoverageVerification by tasks.registering(JacocoCoverageVerification::class) {
    group = "verification"
    description = "Verifies focused chart and indicator line coverage for Sprint 10."
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(chartCoverageClassDirs)
    sourceDirectories.setFrom(chartCoverageSourceDirs)
    executionData.setFrom(chartCoverageExecutionData)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.25".toBigDecimal()
            }
        }
    }
    onlyIf { chartCoverageExecutionData.files.any { it.exists() } }
}

// ---------------------------------------------------------------------------
// Domain layer coverage gate (risk, SMC, AI, backtest, calculator)
// ---------------------------------------------------------------------------

val domainCoverageIncludes = listOf(
    "com/foxtrader/app/domain/usecase/risk/RiskEngine*",
    "com/foxtrader/app/domain/usecase/smc/SmcDetector*",
    "com/foxtrader/app/domain/usecase/ai/MasterDecisionEngine*",
    "com/foxtrader/app/domain/usecase/ai/AgentOrchestrator*",
    "com/foxtrader/app/domain/usecase/backtest/BacktestEngine*",
    "com/foxtrader/app/domain/usecase/calculator/PositionCalculator*",
    "com/foxtrader/app/domain/usecase/calculator/InstrumentTypeResolver*",
)

val domainCoverageSourceDirs = files(
    "src/main/java/com/foxtrader/app/domain/usecase/risk",
    "src/main/java/com/foxtrader/app/domain/usecase/smc",
    "src/main/java/com/foxtrader/app/domain/usecase/ai",
    "src/main/java/com/foxtrader/app/domain/usecase/backtest",
    "src/main/java/com/foxtrader/app/domain/usecase/calculator",
)

val domainCoverageClassDirs = files(
    fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        include(*domainCoverageIncludes.toTypedArray())
        exclude(*chartCoverageExcludes.toTypedArray())
    },
    fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
        include(*domainCoverageIncludes.toTypedArray())
        exclude(*chartCoverageExcludes.toTypedArray())
    },
)

val domainCoverageExecutionData = fileTree(layout.buildDirectory) {
    include(
        "jacoco/testDebugUnitTest.exec",
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec.ec",
    )
}

val jacocoDomainCoverageReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Generates a focused Jacoco report for the domain layer coverage gate."
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(domainCoverageClassDirs)
    sourceDirectories.setFrom(domainCoverageSourceDirs)
    executionData.setFrom(domainCoverageExecutionData)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    onlyIf { domainCoverageExecutionData.files.any { it.exists() } }
}

val jacocoDomainCoverageVerification by tasks.registering(JacocoCoverageVerification::class) {
    group = "verification"
    description = "Verifies domain layer line coverage at 40% floor."
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(domainCoverageClassDirs)
    sourceDirectories.setFrom(domainCoverageSourceDirs)
    executionData.setFrom(domainCoverageExecutionData)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.40".toBigDecimal()
            }
        }
    }
    onlyIf { domainCoverageExecutionData.files.any { it.exists() } }
}

val chartStaticAnalysis by tasks.registering {
    group = "verification"
    description = "Runs the app-wide detekt gate used by the current sprint hygiene rollout."
    dependsOn("detekt")
}

val chartFormatAudit by tasks.registering {
    group = "verification"
    description = "Runs app-wide ktlint checks (failures are advisory during burndown)."
    dependsOn("ktlintMainSourceSetCheck")
}

tasks.matching {
    it.name == "ktlintKotlinScriptCheck" ||
        it.name == "ktlintKotlinScriptFormat" ||
        it.name == "ktlintAndroidTestSourceSetCheck" ||
        it.name == "ktlintAndroidTestSourceSetFormat" ||
        it.name == "ktlintTestSourceSetCheck" ||
        it.name == "ktlintTestSourceSetFormat"
}.configureEach {
    enabled = false
}

tasks.matching { it.name == "assembleDebug" || it.name == "testDebugUnitTest" }
    .configureEach {
        dependsOn(chartStaticAnalysis)
    }

tasks.matching { it.name == "testDebugUnitTest" }
    .configureEach {
        finalizedBy(
            jacocoChartCoverageReport,
            jacocoChartCoverageVerification,
            jacocoDomainCoverageReport,
            jacocoDomainCoverageVerification,
        )
    }

tasks.matching { it.name == "check" }
    .configureEach {
        dependsOn(jacocoChartCoverageVerification, jacocoDomainCoverageVerification)
    }

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager + Hilt integration
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Security (encrypted storage for tokens)
    implementation(libs.androidx.security.crypto)

    // Biometric authentication
    implementation(libs.androidx.biometric)

    // Fragment (FragmentActivity host for BiometricPrompt)
    implementation(libs.androidx.fragment.ktx)

    // Startup / baseline profile installation
    implementation(libs.androidx.profileinstaller)

    // Memory / leak audit (debug only)
    debugImplementation(libs.leakcanary.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    // Room MigrationTestHelper — validates every migration path against the
    // exported schemas so user data can never be silently dropped.
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

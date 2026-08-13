package com.foxtrader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.data.auth.BiometricAuthManager
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.feature.auth.presentation.LockScreen
import com.foxtrader.app.feature.onboarding.presentation.DisclaimerScreen
import com.foxtrader.app.feature.onboarding.presentation.OnboardingScreen
import com.foxtrader.app.ui.navigation.FoxNavHost
import com.foxtrader.app.ui.theme.FoxTraderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host. All screens are Composables rendered inside
 * [FoxNavHost]. [AndroidEntryPoint] enables Hilt injection into this Activity
 * and its ViewModels.
 *
 * Extends [FragmentActivity] (not plain ComponentActivity) so BiometricPrompt
 * can host its authentication UI for gating sensitive actions (H3 security).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* User choice is respected; alerts degrade gracefully if denied. */ }

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            // Drive the theme from the user's dark-mode preference so the
            // Settings toggle actually takes effect app-wide.
            val darkMode by appPreferences.darkMode.collectAsStateWithLifecycle()
            val appLockEnabled by appPreferences.appLockEnabled.collectAsStateWithLifecycle()

            // First-run educational-tool disclaimer must be acknowledged before
            // any analysis is shown (ENTERPRISE_MASTER_PLAN T5.3).
            val disclaimerAcknowledged by appPreferences.disclaimerAcknowledged
                .collectAsStateWithLifecycle()
            val workspaceProfile by appPreferences.workspaceProfile.collectAsStateWithLifecycle()

            // Lock the app on launch when App Lock is on; unlocked for the session
            // once biometric auth succeeds.
            var unlocked by remember { mutableStateOf(false) }
            val locked = appLockEnabled && !unlocked

            FoxTraderAppContent(
                darkMode = darkMode,
                showDisclaimer = !disclaimerAcknowledged,
                onAcknowledgeDisclaimer = { appPreferences.setDisclaimerAcknowledged(true) },
                showOnboarding = disclaimerAcknowledged && !workspaceProfile.completed,
                onFinishOnboarding = { profile ->
                    appPreferences.setWorkspaceProfile(profile)
                    val risk = appPreferences.riskConfig.value.copy(
                        riskPercentPerTrade = profile.suggestedRiskPercent(),
                    )
                    appPreferences.setRiskConfig(risk)
                    appPreferences.setDefaultTimeframe(profile.preferredTimeframe)
                },
                locked = locked,
                onAuthenticate = {
                    biometricAuthManager.authenticate(
                        activity = this@MainActivity,
                        title = "Unlock FoxTrader",
                        subtitle = "Use your biometric or device credential",
                    ) == BiometricAuthManager.BiometricResult.Success
                },
                onUnlock = { unlocked = true },
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Root composable. Named *Content to avoid an overload-resolution clash with
 * the [FoxTraderApp] Application class, which shares this package.
 */
@Composable
private fun FoxTraderAppContent(
    darkMode: Boolean,
    showDisclaimer: Boolean,
    onAcknowledgeDisclaimer: () -> Unit,
    showOnboarding: Boolean,
    onFinishOnboarding: (com.foxtrader.app.domain.model.WorkspaceProfile) -> Unit,
    locked: Boolean,
    onAuthenticate: suspend () -> Boolean,
    onUnlock: () -> Unit,
) {
    FoxTraderTheme(darkTheme = darkMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when {
                // First-run gate: nothing else renders until the educational
                // disclaimer is acknowledged.
                showDisclaimer -> DisclaimerScreen(onAcknowledge = onAcknowledgeDisclaimer)
                showOnboarding -> OnboardingScreen(onFinished = onFinishOnboarding)
                locked -> LockScreen(onAuthenticate = onAuthenticate, onUnlock = onUnlock)
                else -> FoxNavHost()
            }
        }
    }
}

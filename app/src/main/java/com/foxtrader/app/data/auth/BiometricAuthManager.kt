package com.foxtrader.app.data.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Biometric authentication manager for gating sensitive actions.
 *
 * Used to protect:
 * - Revealing API keys / secrets
 * - Placing live orders (future broker SDK)
 * - Exporting/deleting account data
 * - Unlocking the app after timeout (future)
 *
 * Falls back to device credential (PIN/pattern/password) if biometrics are
 * not enrolled but a screen lock is set.
 *
 * SECURITY:
 * - Never stores biometric data — the OS owns it.
 * - Uses Class 2 (BIOMETRIC_WEAK) for broad device compatibility; upgrade to
 *   Class 3 (BIOMETRIC_STRONG) + CryptoObject for Keystore-gated decryption
 *   when H3 is production-hardened.
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Result of a biometric authentication attempt. */
    sealed class BiometricResult {
        data object Success : BiometricResult()
        data class Failed(val reason: String) : BiometricResult()
        data object Cancelled : BiometricResult()
        data object NotAvailable : BiometricResult()
    }

    /**
     * Check whether the device supports biometric or device-credential auth.
     */
    fun canAuthenticate(): Boolean {
        val bm = BiometricManager.from(context)
        val result = bm.canAuthenticate(ALLOWED_AUTHENTICATORS)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Check whether biometrics are enrolled (not just hardware present).
     */
    fun isBiometricEnrolled(): Boolean {
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Show the biometric prompt and suspend until the user authenticates
     * or cancels. Must be called from an Activity context.
     *
     * @param activity The hosting FragmentActivity.
     * @param title Prompt title (e.g. "Authenticate to reveal API key").
     * @param subtitle Optional subtitle.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "Authentication Required",
        subtitle: String? = null,
    ): BiometricResult {
        if (!canAuthenticate()) return BiometricResult.NotAvailable

        return suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(BiometricResult.Success)
                }

                override fun onAuthenticationFailed() {
                    // Called on each failed attempt (e.g. unrecognized fingerprint);
                    // do NOT resume — the system allows retries until error/cancel.
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) {
                        val result = when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED ->
                                BiometricResult.Cancelled
                            else ->
                                BiometricResult.Failed(errString.toString())
                        }
                        cont.resume(result)
                    }
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                .build()

            prompt.authenticate(info)

            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    private companion object {
        /** Allow biometric OR device credential (PIN/pattern/password) as fallback. */
        const val ALLOWED_AUTHENTICATORS =
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
    }
}

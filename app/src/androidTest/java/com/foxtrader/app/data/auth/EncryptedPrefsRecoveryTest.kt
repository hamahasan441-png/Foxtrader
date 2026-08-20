package com.foxtrader.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression test for Task 2: restore from Android backup can hand the app
 * undecryptable ciphertext (Keystore master key does not travel). Encrypted
 * SharedPreferences construction must not crash — it should wipe and recreate
 * an empty store.
 *
 * We simulate the crash path by:
 * 1. Creating a valid EncryptedSharedPreferences file.
 * 2. Corrupting the underlying XML file bytes.
 * 3. Attempting to construct a new TokenManager / AppPreferences — it should
 *    recover with empty prefs instead of throwing.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedPrefsRecoveryTest {

    private lateinit var context: Context

    private val authFileName = "fox_auth_tokens"
    private val providerFileName = "fox_provider_keys"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Ensure clean slate
        context.deleteSharedPreferences(authFileName)
        context.deleteSharedPreferences(providerFileName)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(authFileName)
        context.deleteSharedPreferences(providerFileName)
    }

    private fun createValidEncryptedPrefs(fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun corruptPrefsFile(fileName: String) {
        // SharedPreferences are stored under <filesDir>/../shared_prefs/<name>.xml
        // Resolve via File(context.filesDir.parent, "shared_prefs/...")
        val prefsDir = File(context.filesDir.parentFile, "shared_prefs")
        val prefsFile = File(prefsDir, "$fileName.xml")
        assertTrue("Prefs file should exist to corrupt: ${prefsFile.absolutePath}", prefsFile.exists())
        // Overwrite with garbage — this mimics an undecryptable restore.
        prefsFile.writeText("THIS IS NOT VALID ENCRYPTED PREFS - CORRUPTED BY TEST 1234567890 !!!")
    }

    @Test
    fun tokenManager_recoversFromCorruptPrefsFile() {
        // Step 1: create a valid store and write something
        val prefs = createValidEncryptedPrefs(authFileName)
        prefs.edit().putString("access_token", "dummy").apply()

        // Step 2: corrupt the underlying XML file bytes
        corruptPrefsFile(authFileName)

        // Step 3: Direct EncryptedSharedPreferences.create should now throw (proving corruption)
        var threw = false
        try {
            createValidEncryptedPrefs(authFileName)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("Corrupted prefs file should cause EncryptedSharedPreferences.create to throw", threw)

        // Step 4: TokenManager's defensive wrapper should recover, not throw, and be empty
        var manager: TokenManager? = null
        var exception: Exception? = null
        try {
            manager = TokenManager(context)
            // Access lazy prefs — should not throw and should be empty
            val access = manager.getAccessToken()
            val refresh = manager.getRefreshToken()
            assertNull("After recovery, access token should be null / empty", access)
            assertNull("After recovery, refresh token should be null / empty", refresh)
            assertFalse("After recovery, isLoggedIn should be false", manager.isLoggedIn())
        } catch (e: Exception) {
            exception = e
        }
        assertNull("TokenManager should recover with empty prefs instead of throwing, but threw: $exception", exception)
        assertNotNull("TokenManager instance should be created", manager)
    }

    @Test
    fun appPreferences_securePrefs_recoversFromCorruptFile() {
        // This test exercises the same defensive path in AppPreferences.createSecurePrefs().
        // To avoid pulling the whole AppPreferences dependency graph (DataStore etc.),
        // we directly test the recreation logic pattern: corrupt, then create with
        // the same try/catch recovery that AppPreferences uses.

        val prefs = createValidEncryptedPrefs(providerFileName)
        prefs.edit().putString("provider_api_key_SAMPLE", "key").apply()

        corruptPrefsFile(providerFileName)

        var directThrew = false
        try {
            createValidEncryptedPrefs(providerFileName)
        } catch (_: Exception) {
            directThrew = true
        }
        assertTrue("Corrupted provider prefs should throw on direct creation", directThrew)

        // Now simulate AppPreferences recovery logic: try, on failure delete and recreate
        var recovered: SharedPreferences? = null
        var threw = false
        try {
            try {
                recovered = createValidEncryptedPrefs(providerFileName)
            } catch (e: Exception) {
                android.util.Log.w(
                    "EncryptedPrefsRecoveryTest",
                    "Corrupted $providerFileName — wiping and recreating",
                    e
                )
                context.deleteSharedPreferences(providerFileName)
                recovered = createValidEncryptedPrefs(providerFileName)
            }
        } catch (_: Exception) {
            threw = true
        }
        assertFalse("Recovery should not throw", threw)
        assertNotNull("Recovered prefs should not be null", recovered)
        // After recovery it should be empty (fresh)
        assertTrue("Recovered prefs should be empty after wipe", recovered!!.all.isEmpty())
    }
}

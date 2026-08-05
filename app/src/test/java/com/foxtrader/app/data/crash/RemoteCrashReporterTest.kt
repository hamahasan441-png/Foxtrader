package com.foxtrader.app.data.crash

import android.os.Handler
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RemoteCrashReporter].
 *
 * Verifies opt-in gating, PII stripping, context preservation, and ANR detection.
 * Uses MockK for the [RemoteCrashBackend] and [AppPreferences] dependencies.
 */
class RemoteCrashReporterTest {

    private val crashReportingEnabled = MutableStateFlow(false)
    private val appPreferences: AppPreferences = mockk(relaxed = true) {
        every { this@mockk.crashReportingEnabled } returns crashReportingEnabled
    }
    private val backend: RemoteCrashBackend = mockk(relaxed = true)
    private val anrWatchdog: AnrWatchdog = mockk(relaxed = true)
    private val mockHandler: Handler = mockk(relaxed = true)

    private lateinit var reporter: RemoteCrashReporter

    @Before
    fun setUp() {
        reporter = RemoteCrashReporter(appPreferences, backend, anrWatchdog)
        reporter.mainHandlerProvider = { mockHandler }
    }

    @Test
    fun `opt-out suppresses all remote calls`() {
        crashReportingEnabled.value = false

        val exception = RuntimeException("sensitive user data in message")
        reporter.recordException(exception, mapOf("screen" to "chart"))

        verify(exactly = 0) { backend.captureException(any()) }
    }

    @Test
    fun `opt-in forwards exceptions to backend`() {
        crashReportingEnabled.value = true

        val exception = IllegalStateException("should be stripped")
        reporter.recordException(exception, mapOf("screen" to "settings"))

        verify(exactly = 1) { backend.captureException(any()) }
    }

    @Test
    fun `PII is stripped -- exception messages not in sanitized output`() {
        crashReportingEnabled.value = true

        val sensitiveMessage = "User John traded 500 EURUSD at 1.0850"
        val exception = RuntimeException(sensitiveMessage)
        val sanitized = reporter.sanitize(exception, emptyMap())

        assertEquals("java.lang.RuntimeException", sanitized.type)
        assertTrue(sanitized.frames.isNotEmpty())
        // The exception message must NOT appear anywhere in the sanitized data
        assertTrue(
            sanitized.toString().contains(sensitiveMessage).not(),
        )
    }

    @Test
    fun `context map is preserved in sanitized exception`() {
        crashReportingEnabled.value = true

        val context = mapOf("screen" to "chart", "symbol" to "EURUSD", "timeframe" to "M15")
        val exception = RuntimeException("ignored")
        val sanitized = reporter.sanitize(exception, context)

        assertEquals(context, sanitized.context)
    }

    @Test
    fun `sanitize preserves cause chain up to depth limit`() {
        val root = IllegalArgumentException("root cause")
        val mid = IllegalStateException("mid", root)
        val top = RuntimeException("top", mid)

        val sanitized = reporter.sanitize(top, mapOf("level" to "top"))

        assertEquals("java.lang.RuntimeException", sanitized.type)
        assertEquals("java.lang.IllegalStateException", sanitized.causedBy?.type)
        assertEquals("java.lang.IllegalArgumentException", sanitized.causedBy?.causedBy?.type)
        assertNull(sanitized.causedBy?.causedBy?.causedBy)
    }

    @Test
    fun `setEnabled true starts ANR watchdog`() {
        reporter.setEnabled(true)

        verify(exactly = 1) { anrWatchdog.start(mockHandler, reporter) }
        verify(exactly = 1) { backend.setEnabled(true) }
    }

    @Test
    fun `setEnabled false stops ANR watchdog`() {
        reporter.setEnabled(false)

        verify(exactly = 1) { anrWatchdog.stop() }
        verify(exactly = 1) { backend.setEnabled(false) }
    }

    @Test
    fun `recordBreadcrumb is suppressed when opted out`() {
        crashReportingEnabled.value = false

        reporter.recordBreadcrumb("navigated to settings", "navigation")

        verify(exactly = 0) { backend.addBreadcrumb(any(), any()) }
    }

    @Test
    fun `recordBreadcrumb forwards to backend when opted in`() {
        crashReportingEnabled.value = true

        reporter.recordBreadcrumb("navigated to chart", "navigation")

        verify(exactly = 1) { backend.addBreadcrumb("navigated to chart", "navigation") }
    }
}

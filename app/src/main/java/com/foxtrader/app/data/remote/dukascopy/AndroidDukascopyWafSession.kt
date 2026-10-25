package com.foxtrader.app.data.remote.dukascopy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Solves the jetta AWS WAF challenge in the app's real WebView, once, and
 * keeps the cookie. Headless HTTP clients cannot mint this token; that is
 * why every Dukascopy chart stayed empty after October 2026.
 */
@SuppressLint("SetJavaScriptEnabled")
class AndroidDukascopyWafSession(
    application: Application,
) : DukascopyWafSession, Application.ActivityLifecycleCallbacks {

    private val main = Handler(Looper.getMainLooper())
    private val gate = Mutex()
    private var resumed: WeakReference<Activity>? = null

    @Volatile
    private var cached: String? = null

    @Volatile
    override var userAgent: String? = null
        private set

    @Volatile
    private var failedAtElapsed = 0L

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    override suspend fun token(): String? {
        cached?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (failedAtElapsed != 0L && now - failedAtElapsed < RETRY_BACKOFF_MS) return null
        return gate.withLock {
            cached?.let { return it }
            val minted = acquire()
            if (!minted.attempted || minted.token.isNullOrBlank()) {
                if (minted.attempted) failedAtElapsed = SystemClock.elapsedRealtime()
                null
            } else {
                failedAtElapsed = 0L
                cached = minted.token
                minted.token
            }
        }
    }

    override fun invalidate(used: String?) {
        if (used == null || cached == used) cached = null
    }

    private data class Mint(val token: String?, val attempted: Boolean)

    private suspend fun acquire(): Mint = suspendCancellableCoroutine { cont ->
        main.post {
            if (!cont.isActive) return@post
            val activity = resumed?.get()
            if (activity == null) {
                // The challenge page has to run inside a real window. Retry on
                // the next chart load, once an activity is resumed.
                cont.resume(Mint(token = null, attempted = false))
                return@post
            }
            val webView = try {
                WebView(activity)
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(Mint(token = null, attempted = true))
                return@post
            }
            val parent = activity.window.decorView as? ViewGroup
            var settled = false
            fun finish(token: String?) {
                if (settled) return
                settled = true
                main.removeCallbacksAndMessages(webView)
                parent?.removeView(webView)
                webView.stopLoading()
                webView.destroy()
                if (cont.isActive) cont.resume(Mint(token = token, attempted = true))
            }
            try {
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                userAgent = webView.settings.userAgentString
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                if (parent != null) {
                    // A real viewport, parked off-screen so the challenge can
                    // measure the page without covering the chart or taking taps.
                    webView.layoutParams = FrameLayout.LayoutParams(480, 320)
                    webView.translationX = -4_000f
                    parent.addView(webView)
                }
                webView.loadUrl("$JETTA_ORIGIN/v1/candles/hour/EUR-USD/BID/2024/1")
            } catch (_: Throwable) {
                finish(null)
                return@post
            }
            val poll = object : Runnable {
                var attempts = 0
                override fun run() {
                    if (!cont.isActive || settled) {
                        finish(null)
                        return
                    }
                    val header = CookieManager.getInstance().getCookie(JETTA_ORIGIN)
                    val token = extractAwsWafToken(header)
                    if (!token.isNullOrBlank()) {
                        finish(token)
                        return
                    }
                    attempts++
                    if (attempts >= POLL_ATTEMPTS) {
                        finish(null)
                    } else {
                        main.postAtTime(this, webView, SystemClock.uptimeMillis() + POLL_INTERVAL_MS)
                    }
                }
            }
            cont.invokeOnCancellation { main.post { finish(null) } }
            main.postAtTime(poll, webView, SystemClock.uptimeMillis() + POLL_INTERVAL_MS)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStopped(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val POLL_ATTEMPTS = 48
        const val RETRY_BACKOFF_MS = 30_000L
    }
}

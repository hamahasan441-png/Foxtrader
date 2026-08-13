package com.foxtrader.app.data.auth

import com.foxtrader.app.data.remote.api.SyncApi
import com.foxtrader.app.domain.model.AuthResponse
import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.AuthTokens
import com.foxtrader.app.domain.model.RefreshRequest
import com.foxtrader.app.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.inject.Provider

/**
 * Unit tests for [AuthInterceptor] — the transparent 401 → token-refresh
 * interceptor. This suite focuses on the response-lifecycle contract: when a
 * refresh fails (or no new token materialises), the interceptor must NOT hand
 * the caller an already-closed [Response]; the returned 401 must carry a
 * readable "session expired" body instead.
 */
class AuthInterceptorTest {

    private val tokenManager = mockk<TokenManager>()
    private val syncApi = mockk<SyncApi>()
    private val syncApiProvider = mockk<Provider<SyncApi>>()

    private val request = Request.Builder().url("https://api.foxtrader.io/api/v1/data").build()

    /**
     * Stub every TokenManager method the interceptor may touch so the strict
     * mock never throws "no answer found" for a side-effect call
     * (setAuthState / clearTokens / saveTokens).
     */
    private fun stubTokenManager(
        accessToken: String?,
        refreshToken: String?,
        accessAfterRefresh: String? = accessToken,
    ): Unit {
        every { tokenManager.getAccessToken() } returns accessToken andThen accessAfterRefresh
        every { tokenManager.getRefreshToken() } returns refreshToken
        every { tokenManager.isRefreshTokenExpired() } returns false
        every { tokenManager.setAuthState(any<AuthState>()) } returns Unit
        every { tokenManager.clearTokens() } returns Unit
        every { tokenManager.saveTokens(any<AuthTokens>()) } returns Unit
    }

    private fun stubChain(original: Response): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any<Request>()) } returns original
        return chain
    }

    private fun build401() =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("original 401 body".toResponseBody())
            .build()

    private fun buildOk() =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{\"ok\":true}".toResponseBody())
            .build()

    private fun refreshResponse() =
        AuthResponse(
            tokens = AuthTokens(
                accessToken = "new-access",
                refreshToken = "new-refresh",
                accessExpiresAt = 0L,
                refreshExpiresAt = 0L,
            ),
            user = UserProfile(id = "u1", email = "a@b.c", displayName = "Test", createdAt = 0L),
        )

    @Test
    fun `refresh failure returns a readable 401 instead of the closed response`() {
        stubTokenManager(accessToken = "expired-access-token", refreshToken = "refresh-token")
        every { syncApiProvider.get() } returns syncApi
        coEvery { syncApi.refresh(any<RefreshRequest>()) } throws IOException("refresh endpoint unreachable")

        val chain = stubChain(build401())
        val interceptor = AuthInterceptor(tokenManager, syncApiProvider)

        val result = interceptor.intercept(chain)

        // Must NOT be the closed original: its body reads back cleanly.
        val body = result.body
        assertNotNull(body)
        val text = body?.string()
        assertTrue("expected a readable session-expired body", text?.contains("Session expired") == true)
        // Status must still reflect the session having expired.
        assertEquals(401, result.code)
    }

    @Test
    fun `missing new token after a refresh returns a readable 401`() {
        // First read returns the header token; the post-refresh read yields
        // null, exercising the defensive `?: return ...` branch.
        stubTokenManager(
            accessToken = "expired-access-token",
            refreshToken = "refresh-token",
            accessAfterRefresh = null,
        )
        every { syncApiProvider.get() } returns syncApi
        coEvery { syncApi.refresh(any<RefreshRequest>()) } returns refreshResponse()

        val chain = stubChain(build401())
        val interceptor = AuthInterceptor(tokenManager, syncApiProvider)

        val result = interceptor.intercept(chain)

        val body = result.body
        assertNotNull(body)
        val text = body?.string()
        assertTrue("expected a readable session-expired body", text?.contains("Session expired") == true)
        assertEquals(401, result.code)
    }

    @Test
    fun `successful refresh retries the request and returns the retried response`() {
        stubTokenManager(
            accessToken = "expired-access",
            refreshToken = "refresh-token",
            accessAfterRefresh = "new-access",
        )
        every { syncApiProvider.get() } returns syncApi
        coEvery { syncApi.refresh(any<RefreshRequest>()) } returns refreshResponse()

        // First proceed returns the 401 (triggers refresh), second returns the
        // retried 200.
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any<Request>()) } returns build401() andThen buildOk()

        val interceptor = AuthInterceptor(tokenManager, syncApiProvider)
        val result = interceptor.intercept(chain)

        // The retry response is returned, not the closed 401.
        assertEquals(200, result.code)
        assertEquals("{\"ok\":true}", result.body?.string())
    }

    @Test
    fun `auth endpoint paths skip token attach and return the response unchanged`() {
        val loginRequest = Request.Builder().url("https://api.foxtrader.io/api/v1/auth/login").build()
        val ok = Response.Builder()
            .request(loginRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("auth body".toResponseBody())
            .build()

        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns loginRequest
        every { chain.proceed(any<Request>()) } returns ok

        val interceptor = AuthInterceptor(tokenManager, syncApiProvider)
        val result = interceptor.intercept(chain)

        // No refresh attempted on an auth path; response returned unchanged.
        assertEquals(200, result.code)
        assertEquals("auth body", result.body?.string())
    }
}

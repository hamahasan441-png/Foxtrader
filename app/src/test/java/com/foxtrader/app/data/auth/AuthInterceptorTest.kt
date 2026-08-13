package com.foxtrader.app.data.auth

import com.foxtrader.app.data.remote.api.SyncApi
import com.foxtrader.app.domain.model.AuthResponse
import com.foxtrader.app.domain.model.RefreshRequest
import com.foxtrader.app.domain.model.AuthTokens
import com.foxtrader.app.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.any
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
        every { tokenManager.getAccessToken() } returns "expired-access-token"
        every { tokenManager.getRefreshToken() } returns "refresh-token"
        every { tokenManager.isRefreshTokenExpired() } returns false
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
        // Initial header attach reads "expired-access-token"; the post-refresh
        // read yields null, exercising the defensive `?: return ...` branch.
        every { tokenManager.getAccessToken() } returns "expired-access-token" andThen null
        every { tokenManager.getRefreshToken() } returns "refresh-token"
        every { tokenManager.isRefreshTokenExpired() } returns false
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
}

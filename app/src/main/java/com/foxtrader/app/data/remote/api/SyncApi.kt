package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.AuthResponse
import com.foxtrader.app.domain.model.LoginRequest
import com.foxtrader.app.domain.model.RefreshRequest
import com.foxtrader.app.domain.model.RegisterRequest
import com.foxtrader.app.domain.model.SyncPullResponse
import com.foxtrader.app.domain.model.SyncPushRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * FoxTrader backend API — Authentication & Cloud Sync endpoints.
 *
 * Base URL: same as the main backend (configured in NetworkModule).
 * Auth endpoints are public; sync endpoints require a Bearer token
 * (injected by [com.foxtrader.app.data.auth.AuthInterceptor]).
 *
 * Contract matches the planned FastAPI backend (H3).
 */
interface SyncApi {

    // ========================================================================
    // AUTHENTICATION
    // ========================================================================

    /** Register a new user account. Returns tokens + profile. */
    @POST("/api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    /** Login with email/password. Returns tokens + profile. */
    @POST("/api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    /** Refresh the access token using a valid refresh token. */
    @POST("/api/v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): AuthResponse

    /** Logout — invalidates refresh token server-side. */
    @POST("/api/v1/auth/logout")
    suspend fun logout(@Header("Authorization") bearer: String)

    // ========================================================================
    // CLOUD SYNC
    // ========================================================================

    /**
     * Push local changes to the server.
     * The server merges them and returns a success acknowledgment.
     */
    @POST("/api/v1/sync/push")
    suspend fun pushSync(@Body request: SyncPushRequest)

    /**
     * Pull remote changes since a given timestamp.
     * Called after push to get any changes from other devices.
     *
     * @param since Epoch ms of last successful pull.
     * @param types Optional filter for specific syncable types.
     */
    @GET("/api/v1/sync/pull")
    suspend fun pullSync(
        @Query("since") since: Long,
        @Query("types") types: String? = null,
    ): SyncPullResponse
}

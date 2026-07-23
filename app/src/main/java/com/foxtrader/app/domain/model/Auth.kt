package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Authentication & cloud sync domain models for Horizon 3.
 *
 * These define the client-side contract for the FoxTrader backend
 * (FastAPI + PostgreSQL + JWT). The domain layer owns the models;
 * the data layer implements the network calls and secure storage.
 */

// ============================================================================
// AUTH STATE
// ============================================================================

/** Observable authentication state for the app. */
enum class AuthState {
    /** Not logged in; no tokens present. */
    UNAUTHENTICATED,
    /** Login/register request in flight. */
    AUTHENTICATING,
    /** Logged in with valid tokens. */
    AUTHENTICATED,
    /** Access token expired; refresh in progress. */
    REFRESHING,
    /** Token refresh failed — user must re-login. */
    SESSION_EXPIRED,
}

// ============================================================================
// TOKENS
// ============================================================================

/**
 * JWT token pair (access + refresh).
 * Stored encrypted via EncryptedSharedPreferences on the device.
 */
@Serializable
data class AuthTokens(
    /** Short-lived access token (e.g. 15 min). */
    val accessToken: String,
    /** Long-lived refresh token (e.g. 7 days). */
    val refreshToken: String,
    /** Unix epoch ms when the access token expires. */
    val accessExpiresAt: Long,
    /** Unix epoch ms when the refresh token expires. */
    val refreshExpiresAt: Long,
)

// ============================================================================
// REQUEST / RESPONSE MODELS (mirror the backend API contract)
// ============================================================================

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
)

/** Response from login/register/refresh endpoints. */
@Serializable
data class AuthResponse(
    val tokens: AuthTokens,
    val user: UserProfile,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

// ============================================================================
// USER PROFILE
// ============================================================================

@Serializable
data class UserProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val createdAt: Long,
    /** Backend-assigned device ID for sync conflict attribution. */
    val deviceId: String = "",
)

// ============================================================================
// SYNC MODELS (extend CloudSyncEngine's domain models)
// ============================================================================

/**
 * A versioned sync item envelope for the cloud API.
 * Carries a version counter for conflict resolution beyond simple last-write-wins.
 */
@Serializable
data class SyncEnvelope<T>(
    val id: String,
    val type: SyncableType,
    val data: T,
    val version: Int,
    val updatedAt: Long,
    val deviceId: String,
    val deleted: Boolean = false,
)

/** Types of data that can be synced to the cloud. */
enum class SyncableType {
    JOURNAL,
    DRAWINGS,
    SETTINGS,
    ALERTS,
    WATCHLISTS,
}

/** Request body for pushing local changes to the server. */
@Serializable
data class SyncPushRequest(
    val items: List<SyncEnvelope<String>>,  // data serialized as JSON string
    val lastSyncTimestamp: Long,
    val deviceId: String,
)

/** Response from the sync pull endpoint. */
@Serializable
data class SyncPullResponse(
    val items: List<SyncEnvelope<String>>,
    val serverTimestamp: Long,
    val hasMore: Boolean = false,
)

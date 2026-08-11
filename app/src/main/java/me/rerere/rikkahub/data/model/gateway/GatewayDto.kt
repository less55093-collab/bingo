package me.rerere.rikkahub.data.model.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val code: String,
)

@Serializable
data class SendVerifyCodeRequest(
    val email: String,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

/**
 * Returned by both `/auth/login` and `/auth/refresh`. The refresh token **rotates** on every
 * refresh and the previous one is invalidated immediately, so [refreshToken] must always be
 * persisted when present.
 */
@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 86400,
    @SerialName("token_type") val tokenType: String = "Bearer",
    val user: UserProfile? = null,
)

@Serializable
data class UserProfile(
    val id: Long = 0,
    val email: String = "",
    val username: String = "",
    val role: String = "",
    val balance: Double = 0.0,
    @SerialName("frozen_balance") val frozenBalance: Double = 0.0,
    val concurrency: Int = 0,
    val status: String = "",
    @SerialName("total_recharged") val totalRecharged: Double = 0.0,
)

@Serializable
data class ApiKeyDto(
    val id: Long = 0,
    val key: String = "",
    val name: String = "",
    @SerialName("group_id") val groupId: Int = 0,
    val status: String = "",
    val quota: Long = 0,
    @SerialName("quota_used") val quotaUsed: Long = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    val isUsable: Boolean
        get() = status == "active" && key.startsWith("sk-")
}

@Serializable
data class CreateKeyRequest(
    val name: String,
    @SerialName("group_id") val groupId: Int,
)

/** The gateway paginates list endpoints with this envelope inside `data`. */
@Serializable
data class PagedList<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    val pages: Int = 0,
)

@Serializable
data class RedeemRequest(
    val code: String,
)

@Serializable
data class RedeemResult(
    val type: String = "",
    val value: Double = 0.0,
    val balance: Double? = null,
)

@Serializable
data class RedeemHistoryItem(
    val id: Long = 0,
    val code: String = "",
    val type: String = "",
    val value: Double = 0.0,
    val status: String = "",
    @SerialName("used_by") val usedBy: Long? = null,
    @SerialName("used_at") val usedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

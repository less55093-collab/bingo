package me.rerere.rikkahub.data.api.gateway

import me.rerere.rikkahub.data.model.gateway.ApiKeyDto
import me.rerere.rikkahub.data.model.gateway.CreateKeyRequest
import me.rerere.rikkahub.data.model.gateway.LoginRequest
import me.rerere.rikkahub.data.model.gateway.PagedList
import me.rerere.rikkahub.data.model.gateway.RedeemHistoryItem
import me.rerere.rikkahub.data.model.gateway.RedeemRequest
import me.rerere.rikkahub.data.model.gateway.RedeemResult
import me.rerere.rikkahub.data.model.gateway.RefreshRequest
import me.rerere.rikkahub.data.model.gateway.RegisterRequest
import me.rerere.rikkahub.data.model.gateway.SendVerifyCodeRequest
import me.rerere.rikkahub.data.model.gateway.TokenPair
import me.rerere.rikkahub.data.model.gateway.UserProfile
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * api.bingoapi.top control plane. Inference traffic does not go through here — it goes through
 * [me.rerere.ai.provider.ProviderManager] with the provisioned `sk-` keys.
 *
 * Every method returns a [GatewayEnvelope]; unwrap with `requireData()` so a non-zero `code`
 * surfaces as a [GatewayException] carrying the machine-readable reason.
 */
interface BingoGatewayAPI {

    @POST("api/v1/auth/send-verify-code")
    suspend fun sendVerifyCode(@Body body: SendVerifyCodeRequest): GatewayEnvelope<Unit>

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): GatewayEnvelope<TokenPair>

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): GatewayEnvelope<TokenPair>

    /**
     * Rotates the refresh token: the token passed in is invalidated on success, so the returned
     * one must be persisted before any other refresh is attempted.
     */
    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): GatewayEnvelope<TokenPair>

    @GET("api/v1/user/profile")
    suspend fun getProfile(): GatewayEnvelope<UserProfile>

    @GET("api/v1/keys")
    suspend fun listKeys(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100,
    ): GatewayEnvelope<PagedList<ApiKeyDto>>

    @POST("api/v1/keys")
    suspend fun createKey(@Body body: CreateKeyRequest): GatewayEnvelope<ApiKeyDto>

    @DELETE("api/v1/keys/{id}")
    suspend fun deleteKey(@Path("id") id: Long): GatewayEnvelope<Unit>

    @POST("api/v1/redeem")
    suspend fun redeem(@Body body: RedeemRequest): GatewayEnvelope<RedeemResult>

    @GET("api/v1/redeem/history")
    suspend fun redeemHistory(): GatewayEnvelope<List<RedeemHistoryItem>>

    companion object {
        const val BASE_URL = "https://api.bingoapi.top/"

        /** Base url for inference requests, used by the injected provider settings. */
        const val INFERENCE_BASE_URL = "https://api.bingoapi.top"

        /** Where users buy redeem codes. */
        const val SHOP_URL = "https://pay.ldxp.cn/shop/BPEF2XEE"

        /** Paths that must not carry an Authorization header. */
        val PUBLIC_PATHS = listOf(
            "api/v1/auth/login",
            "api/v1/auth/register",
            "api/v1/auth/refresh",
            "api/v1/auth/send-verify-code",
        )
    }
}

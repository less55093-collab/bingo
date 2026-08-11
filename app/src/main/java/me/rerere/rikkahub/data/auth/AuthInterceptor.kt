package me.rerere.rikkahub.data.auth

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the bearer token to gateway control-plane calls. Auth endpoints are skipped: sending a
 * stale token to `/auth/refresh` would make the gateway reject the refresh itself.
 */
class AuthInterceptor(private val tokenStore: AuthTokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath.trimStart('/')

        if (BingoGatewayAPI.PUBLIC_PATHS.any { path.startsWith(it) }) {
            return chain.proceed(request)
        }

        val token = runBlocking { tokenStore.currentTokens().accessToken }
        if (token.isBlank()) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        )
    }
}

package me.rerere.rikkahub.data.auth

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import me.rerere.rikkahub.data.model.gateway.RefreshRequest
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Refreshes the access token on 401 and retries once.
 *
 * The gateway **rotates** the refresh token on every call and invalidates the previous one
 * immediately (verified: reusing it returns `REFRESH_TOKEN_INVALID`). So concurrent 401s must not
 * each attempt a refresh — the second would present an already-invalidated token and permanently
 * break the session. The mutex serializes them, and the second caller finds the token already
 * renewed and simply reuses it.
 *
 * On unrecoverable failure the session is cleared, which [me.rerere.rikkahub.data.repository.AccountRepository]
 * observes to bounce the user to the login screen.
 */
class TokenAuthenticator(
    private val tokenStore: AuthTokenStore,
    private val apiProvider: () -> BingoGatewayAPI,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()
            .orEmpty()

        // Give up rather than loop: OkHttp calls us again on the retry's 401.
        if (responseCount(response) >= 2) {
            Log.w(TAG, "authenticate: giving up after repeated 401s")
            runBlocking { tokenStore.clear() }
            return null
        }

        val newToken = runBlocking {
            mutex.withLock {
                val current = tokenStore.currentTokens()

                // Another thread already refreshed while we waited for the lock.
                if (current.accessToken.isNotBlank() && current.accessToken != failedToken) {
                    return@withLock current.accessToken
                }
                if (current.refreshToken.isBlank()) return@withLock null

                try {
                    val pair = apiProvider()
                        .refresh(RefreshRequest(refreshToken = current.refreshToken))
                        .let { envelope ->
                            if (!envelope.isSuccess) {
                                Log.w(TAG, "refresh rejected: ${envelope.code} ${envelope.reason}")
                                return@withLock null
                            }
                            envelope.data ?: return@withLock null
                        }
                    tokenStore.saveTokens(
                        accessToken = pair.accessToken,
                        refreshToken = pair.refreshToken,
                        expiresInSeconds = pair.expiresIn,
                    )
                    pair.accessToken
                } catch (e: Exception) {
                    Log.w(TAG, "refresh failed", e)
                    null
                }
            }
        }

        if (newToken == null) {
            runBlocking { tokenStore.clear() }
            return null
        }

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        private const val TAG = "TokenAuthenticator"
    }
}

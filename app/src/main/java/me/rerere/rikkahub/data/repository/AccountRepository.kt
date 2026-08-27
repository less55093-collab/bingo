package me.rerere.rikkahub.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import me.rerere.rikkahub.data.api.gateway.requireData
import me.rerere.rikkahub.data.api.gateway.requireSuccess
import me.rerere.rikkahub.data.auth.AuthTokenStore
import me.rerere.rikkahub.data.auth.KeyProvisioner
import me.rerere.rikkahub.data.auth.ProviderKeys
import me.rerere.rikkahub.data.model.gateway.LoginRequest
import me.rerere.rikkahub.data.model.gateway.RedeemHistoryItem
import me.rerere.rikkahub.data.model.gateway.RedeemRequest
import me.rerere.rikkahub.data.model.gateway.RedeemResult
import me.rerere.rikkahub.data.model.gateway.RegisterRequest
import me.rerere.rikkahub.data.model.gateway.SendVerifyCodeRequest
import me.rerere.rikkahub.data.model.gateway.TokenPair
import me.rerere.rikkahub.data.model.gateway.UserProfile
import me.rerere.rikkahub.data.sync.s3.S3CredentialStore
import me.rerere.rikkahub.data.sync.ChatBackupSync
import me.rerere.rikkahub.data.db.AccountDatabaseManager

sealed interface AuthState {
    /** Startup state, before the token store has been read. */
    data object Loading : AuthState

    data object Unauthenticated : AuthState

    data class Authenticated(val profile: UserProfile?) : AuthState
}

/**
 * Single owner of account state. Everything account-scoped — tokens, profile, the two provisioned
 * inference keys — is written here and nowhere else, so logout can guarantee a clean slate.
 */
class AccountRepository(
    private val api: BingoGatewayAPI,
    private val context: Context,
    private val tokenStore: AuthTokenStore,
    private val keyProvisioner: KeyProvisioner,
    private val s3CredentialStore: S3CredentialStore,
    private val chatBackupSync: ChatBackupSync,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(tokenStore.tokensFlow, tokenStore.profileFlow) { tokens, profile ->
                if (tokens.isPresent) AuthState.Authenticated(profile) else AuthState.Unauthenticated
            }.collect { _state.value = it }
        }
    }

    suspend fun sendVerifyCode(email: String) {
        api.sendVerifyCode(SendVerifyCodeRequest(email = email.trim())).requireSuccess()
    }

    suspend fun register(email: String, password: String, code: String) {
        val pair = api.register(
            RegisterRequest(email = email.trim(), password = password, code = code.trim())
        ).requireData()
        onAuthenticated(pair)
    }

    suspend fun login(email: String, password: String) {
        val pair = api.login(
            LoginRequest(email = email.trim(), password = password)
        ).requireData()
        onAuthenticated(pair)
    }

    /**
     * Persists the session, then provisions inference keys. Key provisioning failure does not fail
     * the login — the user is authenticated and we retry on next launch — but it does mean chat
     * cannot work yet, so it is surfaced by [me.rerere.rikkahub.data.auth.ProviderKeys.isComplete].
     */
    private suspend fun onAuthenticated(pair: TokenPair) {
        val previousDatabase = AccountDatabaseManager.currentDatabaseName(context)
        tokenStore.saveTokens(
            accessToken = pair.accessToken,
            refreshToken = pair.refreshToken,
            expiresInSeconds = pair.expiresIn,
        )
        pair.user?.let { tokenStore.saveProfile(it) }
        runCatching { keyProvisioner.ensureProvisioned() }
            .onFailure { Log.w(TAG, "key provisioning failed after auth", it) }
        if (pair.user == null) runCatching { refreshProfile() }
        val profile = tokenStore.profileFlow.first()
        val targetDatabase = AccountDatabaseManager.databaseName(profile?.id?.takeIf { it > 0 })
        if (previousDatabase != targetDatabase) chatBackupSync.restartApp()
    }

    /** Called on cold start when already authenticated, to self-heal missing or revoked keys. */
    suspend fun ensureKeysProvisioned(): Boolean {
        return runCatching { keyProvisioner.ensureProvisioned() }
            .onFailure { Log.w(TAG, "key provisioning failed on startup", it) }
            .isSuccess
    }

    suspend fun refreshProfile(): UserProfile {
        val profile = api.getProfile().requireData()
        tokenStore.saveProfile(profile)
        return profile
    }

    suspend fun redeem(code: String): RedeemResult {
        val normalized = normalizeRedeemCode(code)
        val result = api.redeem(RedeemRequest(code = normalized)).requireData()
        // Balance is the number users watch; refresh immediately so the UI reflects the top-up.
        runCatching { refreshProfile() }
        return result
    }

    suspend fun redeemHistory(): List<RedeemHistoryItem> = api.redeemHistory().requireData()

    /**
     * Clears the injected keys as well as the tokens. Leaving them in provider settings would hand
     * the next account on this device a spendable key belonging to the previous user.
     */
    suspend fun logout() {
        runCatching { chatBackupSync.uploadNow() }
            .onFailure { Log.w(TAG, "final chat backup failed during logout", it) }
        // Read the profile ID before clearing the auth store so this account's isolated OSS secret
        // can be removed without touching credentials belonging to another account.
        val accountId = tokenStore.profileFlow.first()?.s3CredentialAccountId()
        accountId?.let {
            runCatching { s3CredentialStore.clear(it) }
                .onFailure { error -> Log.w(TAG, "failed to clear S3 credentials on logout", error) }
        }
        runCatching { s3CredentialStore.clearLegacySettingsCredentials() }
            .onFailure { error -> Log.w(TAG, "failed to clear legacy S3 credentials on logout", error) }
        tokenStore.clear()
        runCatching { keyProvisioner.applyToSettings(ProviderKeys()) }
            .onFailure { Log.w(TAG, "failed to clear injected keys on logout", it) }
        chatBackupSync.restartApp()
    }

    companion object {
        private const val TAG = "AccountRepository"

        /**
         * Zero-width characters that ride along when a code is copied out of a chat app. Compared by
         * code point rather than written as literals, which would be invisible in review.
         */
        private val INVISIBLE_CODE_POINTS = intArrayOf(
            0x200B, // zero-width space
            0x200C, // zero-width non-joiner
            0x200D, // zero-width joiner
            0xFEFF, // BOM / zero-width no-break space
        )

        /** Remove copy/paste artifacts without changing the case-sensitive code itself. */
        fun normalizeRedeemCode(raw: String): String = raw
            .filterNot { it.code in INVISIBLE_CODE_POINTS }
            .trim()
    }
}

/** Only positive gateway IDs identify a persisted account namespace. */
internal fun UserProfile?.s3CredentialAccountId(): Long? = this?.id?.takeIf { it > 0 }

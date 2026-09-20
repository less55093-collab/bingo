package me.rerere.rikkahub.data.auth

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.model.gateway.UserProfile
import me.rerere.rikkahub.data.model.gateway.GatewayRouting
import me.rerere.rikkahub.utils.JsonInstant
import java.util.UUID

/**
 * Deliberately a **separate** DataStore from `settings`: that one is serialized wholesale into
 * WebDAV/S3 backups (see WebDavSync.kt:147), and a refresh token in a user's cloud backup is
 * worse than one on disk. Nothing here is ever included in a backup payload.
 */
private val Context.authStore by preferencesDataStore(name = "auth")

data class AuthTokens(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
) {
    val isPresent: Boolean
        get() = accessToken.isNotBlank() && refreshToken.isNotBlank()

    /** Treated as expired slightly early so a request in flight does not race the expiry. */
    fun isExpired(now: Long): Boolean = expiresAt - EXPIRY_SKEW_MS <= now

    companion object {
        const val EXPIRY_SKEW_MS = 60_000L
    }
}

class AuthTokenStore(private val context: Context) {
    private val dataStore = context.authStore
    private val pendingStore = PendingImageTaskStore(context.noBackupFilesDir.resolve("pending_image_tasks"))
    private val pendingMutex = Mutex()
    private val pendingStorageIssues = MutableStateFlow(0)

    /** Number of preserved unreadable records; healthy records are still recoverable. */
    val pendingImageTaskStorageIssuesFlow: Flow<Int> = pendingStorageIssues

    companion object {
        private val ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val EXPIRES_AT = longPreferencesKey("expires_at")
        private val PROFILE = stringPreferencesKey("profile")
        private val CLAUDE_KEY = stringPreferencesKey("claude_api_key")
        private val GPT_KEY = stringPreferencesKey("gpt_api_key")
        private val IMAGE_KEY = stringPreferencesKey("image_api_key")
        private val GATEWAY_ROUTING = stringPreferencesKey("gateway_routing")
        private val PENDING_IMAGE_TASKS = stringPreferencesKey("pending_image_tasks")
        private val PENDING_LEGACY_ACCOUNT_ID = longPreferencesKey("pending_image_tasks_account_id")
        private val TUTORIAL_SHOWN = booleanPreferencesKey("tutorial_shown")
    }

    val tokensFlow: Flow<AuthTokens> = dataStore.data.map { prefs ->
        AuthTokens(
            accessToken = prefs[ACCESS_TOKEN].orEmpty(),
            refreshToken = prefs[REFRESH_TOKEN].orEmpty(),
            expiresAt = prefs[EXPIRES_AT] ?: 0L,
        )
    }

    val profileFlow: Flow<UserProfile?> = dataStore.data.map { prefs ->
        prefs[PROFILE]?.let { runCatching { JsonInstant.decodeFromString<UserProfile>(it) }.getOrNull() }
    }

    val providerKeysFlow: Flow<ProviderKeys> = dataStore.data.map { prefs ->
        ProviderKeys(
            gptKey = prefs[GPT_KEY].orEmpty(),
            imageKey = prefs[IMAGE_KEY].orEmpty(),
        )
    }

    val tutorialShownFlow: Flow<Boolean> = dataStore.data.map { it[TUTORIAL_SHOWN] ?: false }

    val pendingImageTasksFlow: Flow<List<PendingImageTask>> =
        combine(dataStore.data, pendingStore.revision) { _, _ -> currentPendingImageTasks() }
            .distinctUntilChanged()

    suspend fun currentTokens(): AuthTokens = tokensFlow.first()

    suspend fun currentProviderKeys(): ProviderKeys = providerKeysFlow.first()

    val gatewayRoutingFlow: Flow<GatewayRouting> = dataStore.data.map { prefs ->
        prefs[GATEWAY_ROUTING]?.let { JsonInstant.decodeFromString<GatewayRouting>(it) } ?: GatewayRouting()
    }

    suspend fun currentGatewayRouting(): GatewayRouting = gatewayRoutingFlow.first()

    suspend fun saveGatewayRouting(routing: GatewayRouting) {
        dataStore.edit { prefs ->
            prefs[GATEWAY_ROUTING] = JsonInstant.encodeToString(routing)
            prefs[GPT_KEY] = routing.chat?.key.orEmpty()
            prefs[IMAGE_KEY] = routing.image?.key.orEmpty()
            prefs[CLAUDE_KEY] = ""
        }
    }

    suspend fun currentPendingImageTasks(): List<PendingImageTask> = withContext(Dispatchers.IO) {
        pendingMutex.withLock {
            val prefs = dataStore.data.first()
            migrateLegacyTasks(prefs)
            val accountId = prefs.activeTaskAccountId() ?: run {
                pendingStorageIssues.value = 0
                return@withLock emptyList()
            }
            val result = pendingStore.read(accountId)
            pendingStorageIssues.value = result.damagedRecords
            result.tasks
        }
    }

    suspend fun currentTaskAccountId(): Long? = dataStore.data.first().activeTaskAccountId()

    /** Recheck immediately before using a saved provider/key snapshot or delivering its result. */
    suspend fun isCurrentAccount(task: PendingImageTask): Boolean =
        task.accountId != null && task.accountId == currentTaskAccountId()

    private fun Preferences.activeTaskAccountId(): Long? {
        if (this[ACCESS_TOKEN].isNullOrBlank() || this[REFRESH_TOKEN].isNullOrBlank()) return null
        return this[PROFILE]?.let { encoded ->
            runCatching { JsonInstant.decodeFromString<UserProfile>(encoded).id.takeIf { it > 0 } }.getOrNull()
        }
    }

    private suspend fun migrateLegacyTasks(prefs: Preferences) {
        val legacy = prefs[PENDING_IMAGE_TASKS] ?: return
        val accountId = if (prefs[PENDING_LEGACY_ACCOUNT_ID] != null) {
            prefs[PENDING_LEGACY_ACCOUNT_ID]?.takeIf { it > 0 }
        } else prefs.activeTaskAccountId()
        if (accountId == null) pendingStore.preserveUnownedLegacy(legacy)
        else pendingStore.importLegacy(accountId, legacy)
        // The import has synced independent records (including any corrupt bytes) before deleting
        // the preference. A crash before this edit safely replays the import.
        dataStore.edit { current ->
            if (current[PENDING_IMAGE_TASKS] == legacy) {
                current.remove(PENDING_IMAGE_TASKS)
                current.remove(PENDING_LEGACY_ACCOUNT_ID)
            }
        }
    }

    private suspend fun mutatePendingTasks(block: (Long) -> Unit) = withContext(Dispatchers.IO) {
        pendingMutex.withLock {
            val prefs = dataStore.data.first()
            migrateLegacyTasks(prefs)
            val accountId = prefs.activeTaskAccountId() ?: error("请登录原账号后恢复图片任务")
            block(accountId)
        }
    }

    /**
     * Read tokens without suspending. Used once in `RouteActivity.onCreate` to pick the start
     * route, so an authenticated user never sees a login screen flash before being redirected.
     */
    fun tokensBlocking(): AuthTokens = runBlocking { currentTokens() }

    fun tutorialShownBlocking(): Boolean = runBlocking { tutorialShownFlow.first() }

    fun profileBlocking(): UserProfile? = runBlocking { profileFlow.first() }

    suspend fun saveTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = accessToken
            // Refresh responses rotate the token; a null value means "keep the existing one".
            if (!refreshToken.isNullOrBlank()) prefs[REFRESH_TOKEN] = refreshToken
            prefs[EXPIRES_AT] = System.currentTimeMillis() + expiresInSeconds * 1000L
        }
    }

    suspend fun saveProfile(profile: UserProfile) {
        dataStore.edit { it[PROFILE] = JsonInstant.encodeToString(profile) }
    }

    suspend fun saveProviderKeys(keys: ProviderKeys) {
        dataStore.edit { prefs ->
            // Clear the legacy Claude secret rather than retaining an unused key on disk.
            prefs[CLAUDE_KEY] = ""
            prefs[GPT_KEY] = keys.gptKey
            prefs[IMAGE_KEY] = keys.imageKey
        }
    }

    suspend fun setTutorialShown(shown: Boolean) {
        dataStore.edit { it[TUTORIAL_SHOWN] = shown }
    }

    suspend fun savePendingImageTask(task: PendingImageTask) {
        mutatePendingTasks { accountId -> pendingStore.save(accountId, task) }
    }

    suspend fun removePendingImageTask(taskId: String) {
        mutatePendingTasks { accountId -> pendingStore.remove(accountId) { it.taskId == taskId } }
    }

    suspend fun removePendingImageTaskByRequestId(requestId: String) {
        mutatePendingTasks { accountId -> pendingStore.remove(accountId) { it.requestId == requestId } }
    }

    suspend fun setPendingImageTaskId(requestId: String, taskId: String) {
        if (requestId.isBlank() || taskId.isBlank()) return
        mutatePendingTasks { accountId ->
            pendingStore.update(accountId, requestId) { it.copy(taskId = taskId) }
        }
    }

    /** Binds recovery polling to the same configured API key used for submission. */
    suspend fun setPendingImageTaskKeyFingerprint(requestId: String, fingerprint: String) {
        if (requestId.isBlank() || fingerprint.isBlank()) return
        mutatePendingTasks { accountId ->
            pendingStore.update(accountId, requestId) { it.copy(apiKeyFingerprint = fingerprint) }
        }
    }

    suspend fun updatePendingImageTaskResult(requestId: String, paths: List<String>, error: String? = null) {
        mutatePendingTasks { accountId ->
            pendingStore.update(accountId, requestId) { it.copy(completedPaths = paths, deliveryError = error) }
        }
    }

    /** Clear active credentials; recovery records remain isolated until this account signs in. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        pendingMutex.withLock {
            val prefs = dataStore.data.first()
            // Preserve the owner before removing credentials even if disk-full prevents import.
            prefs.activeTaskAccountId()?.let { owner ->
                dataStore.edit { if (it[PENDING_IMAGE_TASKS] != null) it[PENDING_LEGACY_ACCOUNT_ID] = owner }
            }
            try {
                migrateLegacyTasks(dataStore.data.first())
            } finally {
                dataStore.edit { current ->
                    val legacy = current[PENDING_IMAGE_TASKS]
                    val owner = current[PENDING_LEGACY_ACCOUNT_ID]
                    current.clear()
                    if (legacy != null) {
                        current[PENDING_IMAGE_TASKS] = legacy
                        // Zero marks an unknown owner, so a future account never adopts it.
                        current[PENDING_LEGACY_ACCOUNT_ID] = owner ?: 0L
                    }
                }
            }
        }
    }
}

@Serializable
data class PendingImageTask(
    val accountId: Long? = null,
    val conversationId: String? = null,
    val messageId: String? = null,
    val toolCallId: String? = null,
    val operationId: String? = null,
    val itemId: String? = null,
    val completedPaths: List<String> = emptyList(),
    val deliveryError: String? = null,
    val modelSnapshot: me.rerere.ai.provider.Model? = null,
    val providerSnapshot: me.rerere.ai.provider.ProviderSetting? = null,
    val taskId: String? = null,
    val prompt: String = "",
    val sourcePaths: String? = null,
    val modelName: String = "",
    val origin: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val requestId: String = "",
    val modelId: String = "",
    val providerId: String = "",
    /** Gateway identity used by the original async POST; retained across provider edits. */
    val providerBaseUrl: String = "",
    val size: String = "",
    val numOfImages: Int = 1,
    val editing: Boolean = false,
    /** SHA-256 identity of the key used for the original async submission. */
    val apiKeyFingerprint: String = "",
) {
    fun normalized(): PendingImageTask = if (requestId.isNotBlank()) {
        this
    } else {
        copy(requestId = taskId?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString())
    }
}

/** An empty fingerprint means key selection had not happened before the process stopped. */
internal fun PendingImageTask.recoveryApiKeyFingerprint(): String? =
    apiKeyFingerprint.takeIf(String::isNotBlank)

data class ProviderKeys(
    val gptKey: String = "",
    val imageKey: String = "",
) {
    val isComplete: Boolean
        get() = gptKey.isNotBlank() && imageKey.isNotBlank()
}

package me.rerere.rikkahub.data.auth

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.model.gateway.UserProfile
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

    companion object {
        private val ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val EXPIRES_AT = longPreferencesKey("expires_at")
        private val PROFILE = stringPreferencesKey("profile")
        private val CLAUDE_KEY = stringPreferencesKey("claude_api_key")
        private val GPT_KEY = stringPreferencesKey("gpt_api_key")
        private val IMAGE_KEY = stringPreferencesKey("image_api_key")
        private val PENDING_IMAGE_TASKS = stringPreferencesKey("pending_image_tasks")
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

    val pendingImageTasksFlow: Flow<List<PendingImageTask>> = dataStore.data.map { prefs ->
        prefs[PENDING_IMAGE_TASKS]?.let { encoded ->
            runCatching {
                JsonInstant.decodeFromString<List<PendingImageTask>>(encoded)
                    .map(PendingImageTask::normalized)
            }.getOrNull()
        } ?: emptyList()
    }

    suspend fun currentTokens(): AuthTokens = tokensFlow.first()

    suspend fun currentProviderKeys(): ProviderKeys = providerKeysFlow.first()

    suspend fun currentPendingImageTasks(): List<PendingImageTask> = pendingImageTasksFlow.first()

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
        val normalized = task.normalized()
        dataStore.edit { prefs ->
            val tasks = prefs[PENDING_IMAGE_TASKS]?.let { encoded ->
                runCatching {
                    JsonInstant.decodeFromString<List<PendingImageTask>>(encoded)
                        .map(PendingImageTask::normalized)
                }.getOrNull()
            }.orEmpty()
            prefs[PENDING_IMAGE_TASKS] = JsonInstant.encodeToString(
                tasks.filterNot {
                    it.requestId == normalized.requestId ||
                        (normalized.taskId != null && it.taskId == normalized.taskId)
                } + normalized
            )
        }
    }

    suspend fun removePendingImageTask(taskId: String) {
        dataStore.edit { prefs ->
            val tasks = prefs[PENDING_IMAGE_TASKS]?.let { encoded ->
                runCatching {
                    JsonInstant.decodeFromString<List<PendingImageTask>>(encoded)
                        .map(PendingImageTask::normalized)
                }.getOrNull()
            }.orEmpty().filterNot { it.taskId == taskId }
            if (tasks.isEmpty()) prefs.remove(PENDING_IMAGE_TASKS)
            else prefs[PENDING_IMAGE_TASKS] = JsonInstant.encodeToString(tasks)
        }
    }

    suspend fun removePendingImageTaskByRequestId(requestId: String) {
        dataStore.edit { prefs ->
            val tasks = prefs[PENDING_IMAGE_TASKS]?.let { encoded ->
                runCatching {
                    JsonInstant.decodeFromString<List<PendingImageTask>>(encoded)
                        .map(PendingImageTask::normalized)
                }.getOrNull()
            }.orEmpty().filterNot { it.requestId == requestId }
            if (tasks.isEmpty()) prefs.remove(PENDING_IMAGE_TASKS)
            else prefs[PENDING_IMAGE_TASKS] = JsonInstant.encodeToString(tasks)
        }
    }

    suspend fun setPendingImageTaskId(requestId: String, taskId: String) {
        if (requestId.isBlank() || taskId.isBlank()) return
        dataStore.edit { prefs ->
            val tasks = prefs[PENDING_IMAGE_TASKS]?.let { encoded ->
                runCatching {
                    JsonInstant.decodeFromString<List<PendingImageTask>>(encoded)
                        .map(PendingImageTask::normalized)
                }.getOrNull()
            }.orEmpty().map { task ->
                if (task.requestId == requestId) task.copy(taskId = taskId) else task
            }
            if (tasks.isEmpty()) prefs.remove(PENDING_IMAGE_TASKS)
            else prefs[PENDING_IMAGE_TASKS] = JsonInstant.encodeToString(tasks)
        }
    }

    /** Binds recovery polling to the same configured API key used for submission. */
    suspend fun setPendingImageTaskKeyFingerprint(requestId: String, fingerprint: String) {
        if (requestId.isBlank() || fingerprint.isBlank()) return
        dataStore.edit { prefs ->
            val tasks = prefs[PENDING_IMAGE_TASKS]?.let { encoded ->
                runCatching {
                    JsonInstant.decodeFromString<List<PendingImageTask>>(encoded)
                        .map(PendingImageTask::normalized)
                }.getOrNull()
            }.orEmpty().map { task ->
                if (task.requestId == requestId) task.copy(apiKeyFingerprint = fingerprint) else task
            }
            if (tasks.isEmpty()) prefs.remove(PENDING_IMAGE_TASKS)
            else prefs[PENDING_IMAGE_TASKS] = JsonInstant.encodeToString(tasks)
        }
    }

    /** Clears everything account-scoped so the next login cannot inherit these keys. */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}

@Serializable
data class PendingImageTask(
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

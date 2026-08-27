package me.rerere.rikkahub.data.auth

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import me.rerere.rikkahub.data.api.gateway.GatewayGroups
import me.rerere.rikkahub.data.api.gateway.GatewayKeyNames
import me.rerere.rikkahub.data.api.gateway.requireData
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.gateway.ApiKeyDto
import me.rerere.rikkahub.data.model.gateway.CreateKeyRequest

/**
 * A gateway key belongs to exactly one group. Chat and image generation use separate keys,
 * provisioned transparently so the user never sees an API key at all.
 */
class KeyProvisioner(
    private val api: BingoGatewayAPI,
    private val tokenStore: AuthTokenStore,
    private val settingsStore: SettingsStore,
) {
    private val mutex = Mutex()

    /**
     * Idempotent. Safe to call on every cold start: it reconciles what the gateway has against
     * what is stored locally and only issues writes when something is actually missing or dead.
     */
    suspend fun ensureProvisioned(): ProviderKeys = mutex.withLock {
        val remote = api.listKeys().requireData().items
        val stored = tokenStore.currentProviderKeys()

        val gpt = reconcile(
            remote = remote,
            name = GatewayKeyNames.GPT,
            groupId = GatewayGroups.GPT,
            storedSecret = stored.gptKey,
        )
        val image = reconcile(
            remote = remote,
            name = GatewayKeyNames.IMAGE,
            groupId = GatewayGroups.IMAGE,
            storedSecret = stored.imageKey,
        )

        ProviderKeys(gptKey = gpt, imageKey = image).also {
            tokenStore.saveProviderKeys(it)
            applyToSettings(it)
        }
    }

    /**
     * Re-normalizes provider settings from code with the current keys. Safe to call on every start:
     * it skips the write when nothing would change, so it does not churn DataStore or retrigger
     * every settings collector on launch.
     */
    suspend fun applyToSettings(keys: ProviderKeys) {
        settingsStore.update { settings ->
            if (ProviderInjector.isUpToDate(settings, keys)) settings
            else ProviderInjector.inject(settings, keys)
        }
    }

    /**
     * Returns a usable secret for [name], creating or replacing the key as needed.
     *
     * The list endpoint returns the full `sk-` secret (verified), so a key created before a crash
     * is recoverable rather than orphaned — no delete-and-recreate dance required. A key is only
     * replaced when the gateway itself says it is unusable.
     */
    private suspend fun reconcile(
        remote: List<ApiKeyDto>,
        name: String,
        groupId: Int,
        storedSecret: String,
    ): String {
        // Extra keys under the same name would silently double-bill; keep the newest, drop the rest.
        val matches = remote.filter { it.name == name }.sortedByDescending { it.id }
        matches.drop(1).forEach { stale ->
            runCatching { api.deleteKey(stale.id) }
                .onFailure { Log.w(TAG, "failed to delete duplicate key ${stale.id}", it) }
        }

        val existing = matches.firstOrNull()
        if (existing != null) {
            if (existing.isUsable && existing.groupId == groupId) {
                return existing.key.ifBlank { storedSecret }
            }
            // Wrong group, disabled, expired or quota-exhausted: replace it.
            Log.i(TAG, "replacing unusable key $name (status=${existing.status}, group=${existing.groupId})")
            runCatching { api.deleteKey(existing.id) }
                .onFailure { Log.w(TAG, "failed to delete unusable key ${existing.id}", it) }
        }

        val created = api.createKey(CreateKeyRequest(name = name, groupId = groupId)).requireData()
        return created.key
    }

    companion object {
        private const val TAG = "KeyProvisioner"
    }
}

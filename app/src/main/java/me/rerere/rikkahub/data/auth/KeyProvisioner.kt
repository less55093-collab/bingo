package me.rerere.rikkahub.data.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import me.rerere.rikkahub.data.api.gateway.GatewayKeyNames
import me.rerere.rikkahub.data.api.gateway.GatewayModelAPI
import me.rerere.rikkahub.data.api.gateway.InsufficientBalanceException
import me.rerere.rikkahub.data.api.gateway.ApiKeyExpiredException
import me.rerere.rikkahub.data.api.gateway.requireData
import me.rerere.rikkahub.data.model.gateway.*

data class GatewaySyncState(
    val loading: Boolean = false,
    val groups: List<GatewayGroup> = emptyList(),
    val error: String? = null,
)

/** Serializes startup, refresh and switching. Only a complete key + catalog becomes active. */
class KeyProvisioner(
    private val api: BingoGatewayAPI,
    private val store: GatewayCatalogStore,
    private val modelAPI: GatewayModelAPI,
) {
    private val mutex = Mutex()
    private val _syncState = MutableStateFlow(GatewaySyncState())
    val syncState = _syncState.asStateFlow()

    suspend fun ensureProvisioned(): ProviderKeys = refresh(force = false)

    suspend fun refresh(force: Boolean = true): ProviderKeys = mutex.withLock {
        _syncState.value = _syncState.value.copy(loading = true, error = null)
        try {
            // Clear disallowed cached credentials before network I/O, even if startup is offline.
            var routing = restoreRouting()
            val groups = api.availableGroups().requireData().distinctBy { it.id }
            _syncState.value = _syncState.value.copy(groups = groups)
            // Permission revocation must take effect even when the following key/model request fails.
            routing = enforcePermissions(routing, groups)
            val remote = listAllKeys()
            val errors = mutableListOf<String>()
            for (purpose in GatewayPurpose.entries) {
                val previous = if (purpose == GatewayPurpose.CHAT) routing.chat else routing.image
                val group = purpose.availableGroups(groups)
                    .firstOrNull { it.id == (previous?.group?.id ?: purpose.defaultGroupId) }
                if (group == null) {
                    // Never silently move users to another billing group.
                    errors += selectionRequired(purpose)
                    continue
                }
                try {
                    var key = provision(remote, purpose, group.id)
                    val fresh = previous?.key == key && previous.syncedAt > 0 &&
                        System.currentTimeMillis() - previous.syncedAt < 15 * 60_000
                    val models = if (!force && fresh && previous.models.isNotEmpty()) {
                        previous.models
                    } else {
                        try {
                            val remoteModels = modelAPI.listModels(key)
                            if (remoteModels.isNotEmpty()) remoteModels else fallbackModels(purpose, group)
                        } catch (e: ApiKeyExpiredException) {
                            // Key 过期自愈：自动生成新 Key 并再次获取模型
                            key = createNewKey(purpose, group.id)
                            try {
                                val remoteModels = modelAPI.listModels(key)
                                if (remoteModels.isNotEmpty()) remoteModels else fallbackModels(purpose, group)
                            } catch (balanceErr: InsufficientBalanceException) {
                                errors += "账户余额不足，请充值后使用"
                                fallbackModels(purpose, group)
                            } catch (_: Exception) {
                                fallbackModels(purpose, group)
                            }
                        } catch (e: InsufficientBalanceException) {
                            errors += "账户余额不足，请充值后使用"
                            fallbackModels(purpose, group)
                        } catch (e: Exception) {
                            errors += if (purpose == GatewayPurpose.CHAT) "聊天模型同步失败，已载入默认模型" else "生图模型同步失败，已载入默认模型"
                            fallbackModels(purpose, group)
                        }
                    }
                    val binding = GatewayBinding(group, key, models, System.currentTimeMillis())
                    routing = replace(routing, purpose, binding)
                    commit(routing)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += if (purpose == GatewayPurpose.CHAT) "聊天配置保存失败：${e.message}" else "生图配置保存失败：${e.message}"
                }
            }
            _syncState.value = _syncState.value.copy(error = errors.takeIf { it.isNotEmpty() }?.joinToString("；"))
            ProviderKeys(routing.chat?.key.orEmpty(), routing.image?.key.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _syncState.value = _syncState.value.copy(error = "无法同步分组和模型，请检查网络后重试")
            throw e
        } finally {
            _syncState.value = _syncState.value.copy(loading = false)
        }
    }

    suspend fun switchGroup(purpose: GatewayPurpose, groupId: Int) = mutex.withLock {
        _syncState.value = _syncState.value.copy(loading = true, error = null)
        try {
            var routing = restoreRouting()
            if (!purpose.allowsGroup(groupId)) {
                _syncState.value = _syncState.value.copy(error = selectionRequired(purpose))
                return@withLock
            }
            // Revalidate permission immediately before provisioning, rather than trusting cached UI.
            val groups = api.availableGroups().requireData().distinctBy { it.id }
            _syncState.value = _syncState.value.copy(groups = groups)
            routing = enforcePermissions(routing, groups)
            val group = purpose.availableGroups(groups).firstOrNull { it.id == groupId }
                ?: error("该分组已不可用，请刷新后重选")
            var key = provision(listAllKeys(), purpose, group.id)
            val models = try {
                val remoteModels = modelAPI.listModels(key)
                if (remoteModels.isNotEmpty()) remoteModels else fallbackModels(purpose, group)
            } catch (e: ApiKeyExpiredException) {
                key = createNewKey(purpose, group.id)
                try {
                    val remoteModels = modelAPI.listModels(key)
                    if (remoteModels.isNotEmpty()) remoteModels else fallbackModels(purpose, group)
                } catch (_: Exception) {
                    fallbackModels(purpose, group)
                }
            } catch (e: InsufficientBalanceException) {
                _syncState.value = _syncState.value.copy(error = "账户余额不足，请充值后使用")
                fallbackModels(purpose, group)
            } catch (_: Exception) {
                fallbackModels(purpose, group)
            }
            val binding = GatewayBinding(group, key, models, System.currentTimeMillis())
            commit(replace(routing, purpose, binding))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _syncState.value = _syncState.value.copy(error = "切换失败，请刷新后重试。未自动改选分组。")
        } finally {
            _syncState.value = _syncState.value.copy(loading = false)
        }
    }

    suspend fun clearAccount() = mutex.withLock {
        store.clear()
        _syncState.value = GatewaySyncState()
    }

    private suspend fun commit(routing: GatewayRouting) {
        val restricted = routing.enforceGroupRestrictions()
        store.save(restricted)
        store.apply(restricted)
    }

    private suspend fun restoreRouting(): GatewayRouting {
        val cached = store.read()
        val restricted = cached.enforceGroupRestrictions()
        if (restricted != cached) store.save(restricted)
        store.apply(restricted)
        return restricted
    }

    private suspend fun enforcePermissions(routing: GatewayRouting, groups: List<GatewayGroup>): GatewayRouting =
        routing.enforceGroupRestrictions(groups.map { it.id }.toSet()).also { restricted ->
            if (restricted != routing) commit(restricted)
        }

    private fun selectionRequired(purpose: GatewayPurpose): String =
        if (purpose == GatewayPurpose.CHAT) "请在设置中选择有权限的聊天分组"
        else "请在设置中选择有权限的生图分组"

    private fun replace(routing: GatewayRouting, purpose: GatewayPurpose, binding: GatewayBinding): GatewayRouting =
        if (purpose == GatewayPurpose.CHAT) routing.copy(chat = binding)
        else routing.copy(
            image = binding,
            imageHistory = (routing.imageHistory + listOfNotNull(routing.image))
                .filter { it.key.isNotBlank() }
                .distinctBy { it.key to it.models },
        )

    private suspend fun listAllKeys(): List<ApiKeyDto> {
        val keys = mutableListOf<ApiKeyDto>()
        var page = 1
        do {
            val batch = api.listKeys(page = page, pageSize = 100).requireData()
            keys += batch.items
            if (batch.items.isEmpty() || keys.size >= batch.total || page >= batch.pages && batch.pages > 0) break
            page++
        } while (true)
        return keys
    }

    private suspend fun provision(remote: List<ApiKeyDto>, purpose: GatewayPurpose, groupId: Int): String {
        require(purpose.allowsGroup(groupId)) { selectionRequired(purpose) }
        val base = if (purpose == GatewayPurpose.CHAT) GatewayKeyNames.GPT else GatewayKeyNames.IMAGE
        val name = "$base-group-$groupId"
        // Keep keys immutable across groups. Deleting or rebinding one would break pending image tasks.
        val legacyNames = setOf(name, base, "app-gpt", "app-deepseek", "app-gpt-group-$groupId", "app-deepseek-group-$groupId")
        return remote.filter { it.groupId == groupId && it.isUsable && it.name in legacyNames }
            .maxByOrNull { it.id }?.key
            ?: api.createKey(CreateKeyRequest(name, groupId)).requireData().let {
                check(it.groupId == groupId && it.isUsable) { "中转站未返回可用密钥" }
                it.key
            }
    }

    private suspend fun createNewKey(purpose: GatewayPurpose, groupId: Int): String {
        val base = if (purpose == GatewayPurpose.CHAT) GatewayKeyNames.GPT else GatewayKeyNames.IMAGE
        val name = "$base-group-$groupId-${System.currentTimeMillis() % 10000}"
        return api.createKey(CreateKeyRequest(name, groupId)).requireData().let {
            check(it.groupId == groupId && it.isUsable) { "中转站未返回可用密钥" }
            it.key
        }
    }

    private fun fallbackModels(purpose: GatewayPurpose, group: GatewayGroup): List<GatewayModel> {
        return if (purpose == GatewayPurpose.CHAT) {
            when (group.id) {
                29 -> listOf(
                    GatewayModel("deepseek-flash", "DeepSeek-Flash"),
                    GatewayModel("deepseek-v4-pro", "DeepSeek-V4-Pro"),
                    GatewayModel("deepseek-chat", "DeepSeek-Chat"),
                    GatewayModel("deepseek-reasoner", "DeepSeek-Reasoner"),
                )
                16 -> listOf(
                    GatewayModel("gpt-5.6-sol", "GPT-5.6-Sol"),
                    GatewayModel("gpt-5.6-terra", "GPT-5.6-Terra"),
                )
                23 -> listOf(
                    GatewayModel("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet"),
                )
                else -> listOf(
                    GatewayModel("deepseek-flash", "DeepSeek-Flash"),
                    GatewayModel("deepseek-v4-pro", "DeepSeek-V4-Pro"),
                )
            }
        } else {
            listOf(
                GatewayModel("gpt-image-2.5", "AI 绘画 (2.5)"),
                GatewayModel("gpt-image-2", "AI 绘画 (2.0)"),
            )
        }
    }
}

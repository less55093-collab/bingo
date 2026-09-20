package me.rerere.rikkahub.data.auth

import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.BINGO_PROVIDER_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.gateway.*

interface GatewayCatalogStore {
    suspend fun read(): GatewayRouting
    suspend fun save(routing: GatewayRouting)
    suspend fun apply(routing: GatewayRouting)
    suspend fun clear()
}

class AccountGatewayCatalogStore(
    private val tokenStore: AuthTokenStore,
    private val settingsStore: SettingsStore,
) : GatewayCatalogStore {
    override suspend fun read(): GatewayRouting {
        val routing = tokenStore.currentGatewayRouting()
        if (routing.chat != null || routing.image != null) {
            return routing.enforceGroupRestrictions().also { restricted ->
                if (restricted != routing) save(restricted)
            }
        }
        val keys = tokenStore.currentProviderKeys()
        if (!keys.isComplete) return routing
        val models = settingsStore.settingsFlow.value.providers.filter { it.id == BINGO_PROVIDER_ID }.flatMap { it.models }
        fun legacy(type: ModelType, key: String, id: Int) = GatewayBinding(
            GatewayGroup(id, if (type == ModelType.CHAT) "聊天分组" else "生图分组"), key,
            models.filter { it.type == type }.map { GatewayModel(it.modelId, it.displayName) },
        )
        return routing.copy(
            chat = legacy(ModelType.CHAT, keys.gptKey, GatewayPurpose.CHAT.defaultGroupId),
            image = legacy(ModelType.IMAGE, keys.imageKey, GatewayPurpose.IMAGE.defaultGroupId),
        ).also { save(it) }
    }

    override suspend fun save(routing: GatewayRouting) {
        check(tokenStore.currentTokens().isPresent) { "登录状态已失效" }
        tokenStore.saveGatewayRouting(routing.enforceGroupRestrictions())
    }

    override suspend fun apply(routing: GatewayRouting) {
        settingsStore.update { settings ->
            val updated = ProviderInjector.inject(settings, routing)
            if (updated == settings) settings else updated
        }
    }

    override suspend fun clear() {
        tokenStore.clear()
        apply(GatewayRouting())
    }
}

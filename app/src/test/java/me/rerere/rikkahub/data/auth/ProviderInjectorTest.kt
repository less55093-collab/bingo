package me.rerere.rikkahub.data.auth

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.BINGO_PROVIDER
import me.rerere.rikkahub.data.datastore.BingoModelIds
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.gateway.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderInjectorTest {
    private fun binding(id: Int, platform: String, vararg models: String) =
        GatewayBinding(GatewayGroup(id, "Group $id", platform), "sk-group-$id", models.map { GatewayModel(it) }, 123)

    private val routing = GatewayRouting(
        chat = binding(16, "openai", "gpt-5.6-sol", "new-upstream-model"),
        image = binding(2, "openai", "gpt-image-2.5", "custom-image-model"),
    )

    private fun Settings.models() = providers.single().models

    @Test fun `new upstream entries appear without a client allowlist`() {
        val settings = ProviderInjector.inject(Settings(), routing)
        assertEquals(listOf("gpt-5.6-sol", "new-upstream-model", "gpt-image-2.5", "custom-image-model"),
            settings.models().map { it.modelId })
        assertEquals(BingoModelIds.GPT_5_6_SOL, settings.chatModelId)
        assertEquals(BingoModelIds.GPT_IMAGE_2_5, settings.imageGenerationModelId)
    }

    @Test fun `same upstream name in both purposes has separate stable identities and credentials`() {
        val source = GatewayRouting(binding(16, "openai", "shared"), binding(13, "grok", "shared"))
        val first = ProviderInjector.inject(Settings(), source)
        val second = ProviderInjector.inject(first, source.copy(chat = binding(23, "openai", "shared")))
        assertEquals(first.models().map { it.id }, second.models().map { it.id })
        assertEquals(2, first.models().map { it.id }.distinct().size)
        val chat = second.models().single { it.type == ModelType.CHAT }.providerOverwrite as ProviderSetting.OpenAI
        val image = second.models().single { it.type == ModelType.IMAGE }.providerOverwrite as ProviderSetting.OpenAI
        assertEquals("sk-group-23", chat.apiKey)
        assertEquals("sk-group-13", image.apiKey)
        assertTrue(image.useAsyncImageTasks)
    }

    @Test fun `refresh preserves selected models and tools regardless of ordering`() {
        val initial = ProviderInjector.inject(Settings(), routing)
        val model = initial.models().first { it.modelId == "new-upstream-model" }
        val selected = initial.copy(chatModelId = model.id,
            providers = listOf(BINGO_PROVIDER.copy(models = initial.models().map {
                if (it.id == model.id) it.copy(tools = setOf(BuiltInTools.Search)) else it
            })))
        val refreshed = ProviderInjector.inject(selected, routing.copy(chat = routing.chat!!.copy(
            models = routing.chat.models.reversed(), key = "sk-rotated")))
        assertEquals(model.id, refreshed.chatModelId)
        assertEquals(setOf(BuiltInTools.Search), refreshed.models().first { it.id == model.id }.tools)
        assertEquals("sk-rotated", (refreshed.models().first { it.id == model.id }.providerOverwrite as ProviderSetting.OpenAI).apiKey)
    }

    @Test fun `removed models repair assistant and background selections`() {
        val initial = ProviderInjector.inject(Settings(), routing)
        val removed = initial.chatModelId
        val selected = initial.copy(
            titleModelId = removed, suggestionModelId = removed,
            assistants = initial.assistants.map { it.copy(chatModelId = removed) },
            favoriteModels = listOf(removed),
        )
        val result = ProviderInjector.inject(selected, routing.copy(chat = binding(23, "anthropic", "claude-new")))
        val replacement = result.models().first { it.type == ModelType.CHAT }.id
        assertEquals(replacement, result.chatModelId)
        assertEquals(replacement, result.fastModelId)
        assertEquals(replacement, result.titleModelId)
        assertEquals(replacement, result.suggestionModelId)
        assertEquals(replacement, result.ocrModelId)
        assertEquals(replacement, result.compressModelId)
        assertTrue(result.assistants.all { it.chatModelId == null })
        assertTrue(result.favoriteModels.isEmpty())
    }

    @Test fun `an authoritative empty catalog does not resurrect fixed models`() {
        val initial = ProviderInjector.inject(Settings(), routing)
        val result = ProviderInjector.inject(initial, GatewayRouting(binding(16, "openai"), binding(2, "openai")))
        assertTrue(result.models().isEmpty())
        assertEquals(Uuid.NIL, result.chatModelId)
        assertEquals(Uuid.NIL, result.imageGenerationModelId)
    }

    @Test fun `platform routing covers messages chat completions responses and Gemini images`() {
        val claude = ProviderInjector.models(binding(1, "anthropic", "custom"), GatewayPurpose.CHAT).single()
        assertTrue(claude.providerOverwrite is ProviderSetting.Claude)
        val gemini = ProviderInjector.models(binding(2, "gemini", "gemini-image"), GatewayPurpose.IMAGE).single()
        assertEquals("https://api.bingoapi.top/v1beta", (gemini.providerOverwrite as ProviderSetting.Google).baseUrl)
        for (platform in listOf("gemini", "composite", "deepseek", "grok")) {
            val model = ProviderInjector.models(binding(3, platform, "chat"), GatewayPurpose.CHAT).single()
            assertFalse((model.providerOverwrite as ProviderSetting.OpenAI).useResponseApi)
        }
        val openai = ProviderInjector.models(binding(4, "openai", "chat"), GatewayPurpose.CHAT).single()
        assertTrue((openai.providerOverwrite as ProviderSetting.OpenAI).useResponseApi)
    }

    @Test fun `backup removes credentials while preserving all saved references`() {
        val source = GatewayRouting(binding(23, "anthropic", "claude"), binding(2, "gemini", "gemini-image"))
        val settings = ProviderInjector.inject(Settings(), source)
        val clean = ProviderInjector.clear(settings)
        assertEquals(settings.chatModelId, clean.chatModelId)
        assertEquals(settings.imageGenerationModelId, clean.imageGenerationModelId)
        assertEquals(settings.models().map { it.id }, clean.models().map { it.id })
        assertEquals("", (clean.providers.single() as ProviderSetting.OpenAI).apiKey)
        assertEquals("", (clean.models()[0].providerOverwrite as ProviderSetting.Claude).apiKey)
        assertEquals("", (clean.models()[1].providerOverwrite as ProviderSetting.Google).apiKey)
    }

    @Test fun `injection ignores restored provider URLs and credentials`() {
        val stale = Settings(providers = listOf(ProviderSetting.OpenAI(baseUrl = "https://wrong.example/v1", apiKey = "sk-stale")))
        val result = ProviderInjector.inject(stale, routing)
        assertEquals("https://api.bingoapi.top/v1", (result.providers.single() as ProviderSetting.OpenAI).baseUrl)
        assertTrue(result.models().all { (it.providerOverwrite as ProviderSetting.OpenAI).baseUrl == "https://api.bingoapi.top/v1" })
    }

    @Test fun `disallowed cached groups never become active but old image tasks retain recovery credentials`() {
        val old = GatewayRouting(binding(30, "openai", "old-chat"), binding(31, "openai", "old-image"))
        val restricted = old.enforceGroupRestrictions()
        val result = ProviderInjector.inject(ProviderInjector.inject(Settings(), routing), old)
        assertTrue(result.models().isEmpty())
        assertEquals("", (result.providers.single() as ProviderSetting.OpenAI).apiKey)
        assertEquals(Uuid.NIL, result.chatModelId)
        assertEquals(Uuid.NIL, result.imageGenerationModelId)
        assertEquals(30, restricted.chat!!.group.id)
        assertEquals(31, restricted.image!!.group.id)
        assertEquals("", restricted.chat.key)
        assertEquals("", restricted.image.key)
        assertEquals(listOf(old.image), restricted.imageHistory)
        val recoveryModel = ProviderInjector.models(restricted.imageHistory.single(), GatewayPurpose.IMAGE).single()
        assertEquals("sk-group-31", (recoveryModel.providerOverwrite as ProviderSetting.OpenAI).apiKey)
        assertEquals(restricted, restricted.enforceGroupRestrictions())
    }

    @Test fun `group choices are the account permissions intersected with each purpose's allowed ids`() {
        val available = listOf(2, 30, 23, 16, 13).map { GatewayGroup(it, "Group $it") }
        assertEquals(listOf(23, 16), GatewayPurpose.CHAT.availableGroups(available).map { it.id })
        assertEquals(listOf(13, 2), GatewayPurpose.IMAGE.availableGroups(available).map { it.id })
        assertEquals(listOf(16), GatewayPurpose.CHAT.availableGroups(available.filter { it.id != 23 }).map { it.id })
        assertTrue(GatewayPurpose.IMAGE.availableGroups(available.filter { it.id == 23 }).isEmpty())
    }
}

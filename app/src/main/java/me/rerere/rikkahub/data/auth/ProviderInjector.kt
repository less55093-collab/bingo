package me.rerere.rikkahub.data.auth

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import me.rerere.rikkahub.data.datastore.BINGO_PROVIDER
import me.rerere.rikkahub.data.datastore.BINGO_IMAGE_OVERWRITE_ID
import me.rerere.rikkahub.data.datastore.BingoModelIds
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.gateway.GatewayBinding
import me.rerere.rikkahub.data.model.gateway.GatewayPurpose
import me.rerere.rikkahub.data.model.gateway.GatewayRouting
import me.rerere.rikkahub.data.model.gateway.enforceGroupRestrictions
import java.util.UUID
import kotlin.uuid.Uuid

/** Builds the picker from the account's last successful upstream catalog, never a fixed model list. */
object ProviderInjector {
    private val legacyChatIds = mapOf(
        "deepseek-flash" to BingoModelIds.DEEPSEEK_FLASH,
        "deepseek-v4-pro" to BingoModelIds.DEEPSEEK_V4_PRO,
        "deepseek-chat" to BingoModelIds.DEEPSEEK_CHAT,
        "deepseek-reasoner" to BingoModelIds.DEEPSEEK_REASONER,
        "gpt-5.6-sol" to BingoModelIds.DEEPSEEK_FLASH,
        "gpt-5.6-terra" to BingoModelIds.DEEPSEEK_V4_PRO,
        "gpt-5.5" to BingoModelIds.DEEPSEEK_V4_PRO,
        "gpt-5.4" to BingoModelIds.DEEPSEEK_FLASH,
        "gpt-5.4-mini" to BingoModelIds.DEEPSEEK_FLASH,
    )

    fun modelId(id: String, purpose: GatewayPurpose): Uuid =
        (if (purpose == GatewayPurpose.CHAT) legacyChatIds[id]
        else if (id == "gpt-image-2.5") BingoModelIds.GPT_IMAGE_2_5
        else if (id == "gpt-image-2") BingoModelIds.GPT_IMAGE_2
        else null)
            ?: Uuid.parse(UUID.nameUUIDFromBytes("bingo:${purpose.name}:$id".toByteArray(Charsets.UTF_8)).toString())

    // Also used to recover previously submitted image tasks, whose group may no longer be selectable.
    // Active catalogs must go through inject(), which enforces the current group restrictions.
    fun models(binding: GatewayBinding?, purpose: GatewayPurpose): List<Model> = binding?.models.orEmpty().map { remote ->
        val image = purpose == GatewayPurpose.IMAGE
        Model(
            id = modelId(remote.id, purpose),
            modelId = remote.id,
            displayName = remote.displayName.ifBlank { remote.id },
            type = if (image) ModelType.IMAGE else ModelType.CHAT,
            inputModalities = if (image) listOf(Modality.TEXT, Modality.IMAGE)
                else ModelRegistry.MODEL_INPUT_MODALITIES.getData(remote.id),
            outputModalities = if (image) listOf(Modality.IMAGE) else listOf(Modality.TEXT),
            abilities = if (image) emptyList() else
                (ModelRegistry.MODEL_ABILITIES.getData(remote.id) + ModelAbility.TOOL).distinct(),
            providerOverwrite = if (image) imageProvider(binding!!, remote.id) else chatProvider(binding!!),
        )
    }

    private fun chatProvider(binding: GatewayBinding): ProviderSetting =
        if (binding.group.claudeCodeOnly || binding.group.platform == "anthropic") {
            ProviderSetting.Claude(
                id = BINGO_PROVIDER.id, name = binding.group.displayName,
                baseUrl = "${BingoGatewayAPI.INFERENCE_BASE_URL}/v1", apiKey = binding.key,
            )
        } else {
            BINGO_PROVIDER.copy(name = binding.group.displayName, apiKey = binding.key,
                useResponseApi = binding.group.platform == "openai")
        }

    fun imageProvider(binding: GatewayBinding, model: String): ProviderSetting {
        val google = binding.group.platform in setOf("gemini", "antigravity") ||
            (binding.group.platform == "composite" &&
                (model.startsWith("gemini", true) || model.contains("nano-banana", true)))
        return if (google) {
            ProviderSetting.Google(
                id = BINGO_IMAGE_OVERWRITE_ID, name = binding.group.displayName,
                baseUrl = "${BingoGatewayAPI.INFERENCE_BASE_URL}/v1beta", apiKey = binding.key,
            )
        } else {
            ProviderSetting.OpenAI(
                id = BINGO_IMAGE_OVERWRITE_ID, name = binding.group.displayName,
                baseUrl = "${BingoGatewayAPI.INFERENCE_BASE_URL}/v1", apiKey = binding.key,
                useAsyncImageTasks = binding.group.platform in setOf("openai", "grok"),
            )
        }
    }

    fun inject(settings: Settings, routing: GatewayRouting): Settings {
        val active = routing.enforceGroupRestrictions()
        val existing = settings.providers.flatMap { it.models }.associateBy { it.id }
        val models = (models(active.chat, GatewayPurpose.CHAT) + models(active.image, GatewayPurpose.IMAGE))
            .distinctBy { it.id }
            .map { model -> model.copy(tools = (existing[model.id]?.tools ?: model.tools)
                .filterNot { it == BuiltInTools.ImageGeneration }.toSet()) }
        val chatIds = models.filter { it.type == ModelType.CHAT }.map { it.id }
        val imageModels = models.filter { it.type == ModelType.IMAGE }
        val imageIds = imageModels.map { it.id }
        val chatDefault = BingoModelIds.DEEPSEEK_FLASH.takeIf { it in chatIds }
            ?: BingoModelIds.GPT_5_6_SOL.takeIf { it in chatIds }
            ?: chatIds.firstOrNull() ?: Uuid.NIL
        val migrateDefault = (!settings.solDefaultApplied && BingoModelIds.DEEPSEEK_FLASH in chatIds)
            || (!settings.solDefaultApplied && BingoModelIds.GPT_5_6_SOL in chatIds)
        // This only chooses a default; it never removes unfamiliar upstream models from the picker.
        val imageDefault = imageModels.firstOrNull { it.modelId == "gpt-image-2.5" }?.id
            ?: imageModels.firstOrNull { looksLikeImageModel(it.modelId) }?.id
            ?: imageIds.firstOrNull() ?: Uuid.NIL
        fun chat(id: Uuid) = id.takeIf { it in chatIds } ?: chatDefault
        val defaultImageId = (BingoModelIds.GPT_IMAGE_2_5.takeIf { it in imageIds }
            ?: settings.imageGenerationModelId.takeIf { it in imageIds }) ?: imageDefault
        return settings.copy(
            providers = listOf(BINGO_PROVIDER.copy(apiKey = active.chat?.key.orEmpty(), models = models)),
            chatModelId = if (migrateDefault) (BingoModelIds.DEEPSEEK_FLASH.takeIf { it in chatIds } ?: BingoModelIds.GPT_5_6_SOL) else chat(settings.chatModelId),
            solDefaultApplied = settings.solDefaultApplied || migrateDefault,
            fastModelId = chat(settings.fastModelId),
            titleModelId = settings.titleModelId?.let(::chat),
            translateModeId = chat(settings.translateModeId),
            suggestionModelId = settings.suggestionModelId?.let(::chat),
            ocrModelId = chat(settings.ocrModelId),
            compressModelId = chat(settings.compressModelId),
            imageGenerationModelId = defaultImageId,
            assistants = settings.assistants.map { assistant ->
                assistant.copy(
                    chatModelId = if (migrateDefault) (BingoModelIds.DEEPSEEK_FLASH.takeIf { it in chatIds } ?: BingoModelIds.GPT_5_6_SOL) else assistant.chatModelId?.takeIf { it in chatIds }
                )
            },
            favoriteModels = settings.favoriteModels.filter { it in chatIds || it in imageIds },
        )
    }

    private fun looksLikeImageModel(id: String): Boolean =
        id.contains("image", true) || id.contains("dall-e", true) ||
            id.contains("imagen", true) || id.contains("banana", true) || id.contains("flux", true)

    /** Backups keep model references but never account credentials. */
    fun clear(settings: Settings): Settings = settings.copy(
        providers = settings.providers.map { provider ->
            clearKey(provider).copyProvider(models = provider.models.map { model ->
                model.copy(providerOverwrite = model.providerOverwrite?.let(::clearKey))
            })
        },
    )

    private fun clearKey(provider: ProviderSetting): ProviderSetting = when (provider) {
        is ProviderSetting.OpenAI -> provider.copy(apiKey = "")
        is ProviderSetting.Claude -> provider.copy(apiKey = "")
        is ProviderSetting.Google -> provider.copy(apiKey = "", privateKey = "", serviceAccountEmail = "")
    }
}

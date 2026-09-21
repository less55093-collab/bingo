package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import kotlin.uuid.Uuid

/**
 * The single provider the app ships with. Users never see or edit provider configuration; keys are
 * provisioned by [me.rerere.rikkahub.data.auth.KeyProvisioner] and written in by
 * [me.rerere.rikkahub.data.auth.ProviderInjector].
 *
 * Every id here is **hardcoded and must never change**: `Settings.chatModelId` and friends are Uuid
 * references, so a regenerated id silently breaks the user's model selection across an update.
 */
val BINGO_PROVIDER_ID: Uuid = Uuid.parse("dde5c839-9351-4ed7-8472-7bb98c829d93")

/** Id of the synthetic OpenAI provider embedded in the image model's `providerOverwrite`. */
val BINGO_IMAGE_OVERWRITE_ID: Uuid = Uuid.parse("3ac9d1f7-8e62-4b0d-95c4-1f7a6e2b8d50")

object BingoModelIds {
    val DEEPSEEK_FLASH: Uuid = Uuid.parse("98765432-0000-0000-0000-000000000001")
    val DEEPSEEK_V4_PRO: Uuid = Uuid.parse("98765432-0000-0000-0000-000000000002")

    // Aliases
    val DEEPSEEK_CHAT: Uuid get() = DEEPSEEK_FLASH
    val DEEPSEEK_REASONER: Uuid get() = DEEPSEEK_V4_PRO
    val GPT_5_5: Uuid get() = DEEPSEEK_FLASH
    val GPT_5_4: Uuid get() = DEEPSEEK_FLASH
    val GPT_5_4_MINI: Uuid get() = DEEPSEEK_FLASH
    val GPT_5_6_SOL: Uuid get() = DEEPSEEK_FLASH
    val GPT_5_6_TERRA: Uuid get() = DEEPSEEK_V4_PRO
    val GPT_IMAGE_2: Uuid = Uuid.parse("7f4a1c2e-6d38-4b95-9a17-0c5e8b3d42f1")
}

/**
 * Image generation lives on its own gateway group (rate multiplier 0.01), so it needs its own key
 * and therefore its own overwrite even though it is the same `openai` platform as the chat
 * container. Typed `OpenAI` because `OpenAIProvider.generateImage` requires that subtype and reads
 * `apiKey`/`baseUrl` straight off it.
 */
private fun imageOverwrite() = ProviderSetting.OpenAI(
    id = BINGO_IMAGE_OVERWRITE_ID,
    name = "bingo-image",
    baseUrl = "${BingoGatewayAPI.INFERENCE_BASE_URL}/v1",
    chatCompletionsPath = "/chat/completions",
    apiKey = "",
    enabled = true,
    builtIn = true,
    useAsyncImageTasks = true,
)

private fun deepseekModel(
    id: Uuid,
    modelId: String,
    displayName: String,
    abilities: List<ModelAbility> = listOf(ModelAbility.TOOL),
) = Model(
    id = id,
    modelId = modelId,
    displayName = displayName,
    inputModalities = listOf(Modality.TEXT),
    outputModalities = listOf(Modality.TEXT),
    abilities = abilities,
)

/**
 * Curated DeepSeek models served via group 29.
 */
val BINGO_MODELS: List<Model> = listOf(
    deepseekModel(
        BingoModelIds.DEEPSEEK_FLASH,
        "deepseek-flash",
        "DeepSeek-Flash",
        abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
    ),
    deepseekModel(
        BingoModelIds.DEEPSEEK_V4_PRO,
        "deepseek-v4-pro",
        "DeepSeek-V4-Pro",
        abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
    ),
    Model(
        id = BingoModelIds.GPT_IMAGE_2,
        modelId = "gpt-image-2",
        displayName = "AI 绘画",
        type = ModelType.IMAGE,
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = listOf(Modality.IMAGE),
        abilities = emptyList(),
        providerOverwrite = imageOverwrite(),
    ),
)

/** The only image model, so image generation never needs a picker. */
val BINGO_IMAGE_MODEL_ID: Uuid = BingoModelIds.GPT_IMAGE_2

/** Default chat model for a fresh install. */
val BINGO_DEFAULT_MODEL_ID: Uuid = BingoModelIds.DEEPSEEK_FLASH

/** Cheapest capable model, used for titles/suggestions/translation background calls. */
val BINGO_FAST_MODEL_ID: Uuid = BingoModelIds.DEEPSEEK_FLASH

/**
 * The DeepSeek key lives on this container and the image key is kept on the image model overwrite.
 * ProviderInjector rewrites both on every launch, so stale or restored values cannot persist.
 */
val BINGO_PROVIDER: ProviderSetting.OpenAI = ProviderSetting.OpenAI(
    id = BINGO_PROVIDER_ID,
    name = "AI",
    baseUrl = "${BingoGatewayAPI.INFERENCE_BASE_URL}/v1",
    chatCompletionsPath = "/chat/completions",
    apiKey = "",
    enabled = true,
    builtIn = true,
    useResponseApi = false,
    models = BINGO_MODELS,
)

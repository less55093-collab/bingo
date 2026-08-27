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
    val GPT_5_5: Uuid = Uuid.parse("0d351496-df92-4d6e-b437-5af058531fb8")
    val GPT_5_4: Uuid = Uuid.parse("281e5862-b5d3-42e1-bb41-d2c936eab76b")
    val GPT_5_4_MINI: Uuid = Uuid.parse("6a3d6d68-0b92-4ce7-9fb8-e3fa6edc0499")
    val GPT_5_6_SOL: Uuid = Uuid.parse("b18c7a54-9e2f-4d63-8f0a-71c4de5a9b32")
    val GPT_5_6_TERRA: Uuid = Uuid.parse("3ed90b7c-45a1-4e28-9c6b-8a2f01d7e5b4")
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

private fun gptModel(id: Uuid, modelId: String, displayName: String) = Model(
    id = id,
    modelId = modelId,
    displayName = displayName,
    inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
    outputModalities = listOf(Modality.TEXT),
    abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
)

/**
 * Curated rather than discovered via `/v1/models`: the menu must be stable and every entry must
 * actually work. Verified against the live gateway — bare `gpt-5.6` and `gpt-5.2-pro` are excluded
 * because they return `upstream_error` on group 16 and a consumer app cannot ship a dead model.
 * The `-sol` / `-terra` variants are distinct upstream routes and were probed directly on group 16
 * (both returned a normal completion), which is why they ship even though bare `gpt-5.6` does not.
 */
val BINGO_MODELS: List<Model> = listOf(
    gptModel(BingoModelIds.GPT_5_6_SOL, "gpt-5.6-sol", "GPT-5.6 sol"),
    gptModel(BingoModelIds.GPT_5_6_TERRA, "gpt-5.6-terra", "GPT-5.6 terra"),
    gptModel(BingoModelIds.GPT_5_5, "gpt-5.5", "GPT-5.5"),
    gptModel(BingoModelIds.GPT_5_4, "gpt-5.4", "GPT-5.4"),
    gptModel(BingoModelIds.GPT_5_4_MINI, "gpt-5.4-mini", "GPT-5.4 mini"),
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
val BINGO_DEFAULT_MODEL_ID: Uuid = BingoModelIds.GPT_5_6_SOL

/** Cheapest capable model, used for titles/suggestions/translation background calls. */
val BINGO_FAST_MODEL_ID: Uuid = BingoModelIds.GPT_5_4_MINI

/**
 * The GPT key lives on this container and the image key is kept on the image model overwrite.
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
    useResponseApi = true,
    models = BINGO_MODELS,
)

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

/** Id of the synthetic Claude provider embedded in each Claude model's `providerOverwrite`. */
val BINGO_CLAUDE_OVERWRITE_ID: Uuid = Uuid.parse("21b67ea5-05a0-4b81-8fe0-5f32f3957ea0")

/** Id of the synthetic OpenAI provider embedded in the image model's `providerOverwrite`. */
val BINGO_IMAGE_OVERWRITE_ID: Uuid = Uuid.parse("3ac9d1f7-8e62-4b0d-95c4-1f7a6e2b8d50")

object BingoModelIds {
    val CLAUDE_FABLE_5: Uuid = Uuid.parse("ffa51803-133d-49ec-b4f3-b3fd8e4b6c36")
    val CLAUDE_OPUS_5: Uuid = Uuid.parse("2cf4efc8-4502-4374-8d2c-ab16fcdf1b7d")
    val CLAUDE_SONNET_5: Uuid = Uuid.parse("9c27fbcf-bc7e-41b1-ba4d-74e1a4a119be")
    val CLAUDE_SONNET_4_6: Uuid = Uuid.parse("d42e705c-cc62-4dc1-a536-b0b0e84f09b1")
    val CLAUDE_HAIKU_4_5: Uuid = Uuid.parse("cdf30a19-ec5a-43b2-a325-219887caa9ae")
    val GPT_5_5: Uuid = Uuid.parse("0d351496-df92-4d6e-b437-5af058531fb8")
    val GPT_5_4: Uuid = Uuid.parse("281e5862-b5d3-42e1-bb41-d2c936eab76b")
    val GPT_5_4_MINI: Uuid = Uuid.parse("6a3d6d68-0b92-4ce7-9fb8-e3fa6edc0499")
    val GPT_5_6_SOL: Uuid = Uuid.parse("b18c7a54-9e2f-4d63-8f0a-71c4de5a9b32")
    val GPT_5_6_TERRA: Uuid = Uuid.parse("3ed90b7c-45a1-4e28-9c6b-8a2f01d7e5b4")
    val GPT_IMAGE_2: Uuid = Uuid.parse("7f4a1c2e-6d38-4b95-9a17-0c5e8b3d42f1")
}

/**
 * Claude models route through a `providerOverwrite` rather than a second visible provider, so the
 * model picker stays one flat list with no provider branding. `Model.findProvider()` swaps in this
 * setting wholesale, which is what gets requests onto `ClaudeProvider` and the `/v1/messages`
 * endpoint with `x-api-key` headers.
 *
 * [apiKey] is a placeholder; the real value is injected at runtime and never persisted from here.
 */
private fun claudeOverwrite() = ProviderSetting.Claude(
    id = BINGO_CLAUDE_OVERWRITE_ID,
    name = "bingo-claude",
    baseUrl = "${BingoGatewayAPI.INFERENCE_BASE_URL}/v1",
    apiKey = "",
    enabled = true,
    builtIn = true,
)

private fun claudeModel(id: Uuid, modelId: String, displayName: String) = Model(
    id = id,
    modelId = modelId,
    displayName = displayName,
    inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
    outputModalities = listOf(Modality.TEXT),
    abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
    providerOverwrite = claudeOverwrite(),
)

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
    claudeModel(BingoModelIds.CLAUDE_FABLE_5, "claude-fable-5", "Claude Fable 5"),
    claudeModel(BingoModelIds.CLAUDE_OPUS_5, "claude-opus-5", "Claude Opus 5"),
    claudeModel(BingoModelIds.CLAUDE_SONNET_5, "claude-sonnet-5", "Claude Sonnet 5"),
    claudeModel(BingoModelIds.CLAUDE_SONNET_4_6, "claude-sonnet-4-6", "Claude Sonnet 4.6"),
    claudeModel(BingoModelIds.CLAUDE_HAIKU_4_5, "claude-haiku-4-5-20251001", "Claude Haiku 4.5"),
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
val BINGO_DEFAULT_MODEL_ID: Uuid = BingoModelIds.CLAUDE_SONNET_5

/** Cheapest capable model, used for titles/suggestions/translation background calls. */
val BINGO_FAST_MODEL_ID: Uuid = BingoModelIds.CLAUDE_HAIKU_4_5

/**
 * The GPT key lives on this container; Claude models override it per-model. Both halves are
 * rewritten on every launch by ProviderInjector, so a stale or restored value cannot persist.
 */
val BINGO_PROVIDER: ProviderSetting.OpenAI = ProviderSetting.OpenAI(
    id = BINGO_PROVIDER_ID,
    name = "AI",
    baseUrl = "${BingoGatewayAPI.INFERENCE_BASE_URL}/v1",
    chatCompletionsPath = "/chat/completions",
    apiKey = "",
    enabled = true,
    builtIn = true,
    models = BINGO_MODELS,
)

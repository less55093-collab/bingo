package me.rerere.rikkahub.data.datastore

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

/** Legacy references retained so an upgrade preserves existing model selections when available upstream. */
val BINGO_IMAGE_MODEL_ID: Uuid = BingoModelIds.GPT_IMAGE_2
val BINGO_DEFAULT_MODEL_ID: Uuid = BingoModelIds.GPT_5_6_SOL
val BINGO_FAST_MODEL_ID: Uuid = BingoModelIds.GPT_5_4_MINI

/** The catalog starts empty and is populated from the authenticated group's /v1/models response. */
val BINGO_PROVIDER: ProviderSetting.OpenAI = ProviderSetting.OpenAI(
    id = BINGO_PROVIDER_ID,
    name = "AI",
    baseUrl = "${BingoGatewayAPI.INFERENCE_BASE_URL}/v1",
    chatCompletionsPath = "/chat/completions",
    apiKey = "",
    enabled = true,
    builtIn = true,
    useResponseApi = true,
    models = emptyList(),
)

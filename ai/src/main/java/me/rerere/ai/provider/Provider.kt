package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage

/**
 * A durable image request reached a state that cannot become successful by polling or replaying it.
 * Callers must remove their local recovery record and surface the failure to the user.
 */
open class ImageGenerationTerminalException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported")
    }

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }

    suspend fun resumeImageTask(
        providerSetting: ProviderSetting,
        taskId: String,
        customHeaders: List<CustomHeader> = emptyList(),
        traceId: String = "",
        apiKeyFingerprint: String = "",
        onTaskFailed: suspend (String) -> Unit = {},
    ): Flow<ImageGenerationItem> {
        error("Asynchronous image tasks are not supported")
    }
}

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    /** Internal-only correlation ID for performance logs. It is never sent to an API. */
    @Transient val traceId: String = "",
    /** Stable key used by asynchronous gateways to make a lost submit response retry-safe. */
    @Transient val idempotencyKey: String = "",
    /** Called after the gateway has durably accepted an asynchronous task. */
    @Transient val onTaskSubmitted: suspend (String) -> Unit = {},
    /** Called after selecting the outbound API key, before sending the billable request. */
    @Transient val onImageKeySelected: suspend (String) -> Unit = {},
    /** Called only when an explicitly unsupported async endpoint is downgraded to sync. */
    @Transient val onAsyncFallback: suspend () -> Unit = {},
    /** Recovery must never downgrade to sync because an earlier async POST may already be billed. */
    @Transient val allowSynchronousFallback: Boolean = true,
    /** Called when the gateway reports a terminal task failure or rejects async submission. */
    @Transient val onTaskFailed: suspend (String) -> Unit = {},
    /**
     * Recovery-only key binding. `null` means a new request may use the normal roulette; any
     * non-null value requires the exact previously persisted key and must fail before POST when
     * that key is no longer configured.
     */
    @Transient val requiredApiKeyFingerprint: String? = null,
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    /** Internal-only correlation ID for performance logs. It is never sent to an API. */
    @Transient val traceId: String = "",
    /** Stable key used by asynchronous gateways to make a lost submit response retry-safe. */
    @Transient val idempotencyKey: String = "",
    /** Called after the gateway has durably accepted an asynchronous task. */
    @Transient val onTaskSubmitted: suspend (String) -> Unit = {},
    /** Called after selecting the outbound API key, before sending the billable request. */
    @Transient val onImageKeySelected: suspend (String) -> Unit = {},
    /** Called only when an explicitly unsupported async endpoint is downgraded to sync. */
    @Transient val onAsyncFallback: suspend () -> Unit = {},
    /** See [ImageGenerationParams.allowSynchronousFallback]. */
    @Transient val allowSynchronousFallback: Boolean = true,
    /** Called when the gateway reports a terminal task failure or rejects async submission. */
    @Transient val onTaskFailed: suspend (String) -> Unit = {},
    /** See [ImageGenerationParams.requiredApiKeyFingerprint]. */
    @Transient val requiredApiKeyFingerprint: String? = null,
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)

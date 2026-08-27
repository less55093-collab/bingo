package me.rerere.ai.provider.providers

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageGenerationTerminalException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.keyCandidates
import me.rerere.ai.util.keyFingerprint
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.ConnectionPool
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAIProvider"

private fun effectiveImageSize(size: String): String =
    size.takeUnless { it.isBlank() || it.equals(ImageGenSize.AUTO.value, ignoreCase = true) }
        ?: ImageGenSize.SQUARE_1024.value

private class ImageTaskNotFoundException(message: String) : ImageGenerationTerminalException(message)

/**
 * The result object has expired at the gateway. This is terminal for the persisted task: retrying
 * the poll (or replaying the original image POST) cannot recover the object and could duplicate a
 * billable generation.
 */
private class ImageTaskResultExpiredException(
    taskId: String,
    detail: String,
) : ImageGenerationTerminalException(
    "Image task result expired (task=$taskId, HTTP 410): $detail",
)

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null,
    private val imageTaskPollIntervalMillis: Long = IMAGE_TASK_POLL_INTERVAL_MS,
) : Provider<ProviderSetting.OpenAI> {
    private val appContext = context?.applicationContext
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    // A reasoning stream can pause for longer than normal request traffic. Keep it on a dedicated
    // client so its socket stays alive across long output and mobile network idle periods.
    private val streamingClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(CHAT_HTTP2_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // A 307/308 preserves a POST body. Streaming requests are billable and cannot safely be
        // replayed without a provider-specific idempotency contract.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val chatCompletionsAPI = ChatCompletionsAPI(client = streamingClient, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = streamingClient, keyRoulette = keyRoulette)
    private val imageRequestClient = client.newBuilder()
        // Image POSTs are long-lived and billable. Never reuse a chat socket that an OEM may have
        // silently killed in the background; keep a negotiated HTTP/2 call alive while it waits.
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        // A slow image provider is not a failed request. Keep transport read/write/call limits
        // disabled; cancellation (user action, process teardown, or an explicit retry boundary)
        // remains the only lifecycle stop for image generation and task polling.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        // A provider may legitimately remain silent for an arbitrary amount of time. A client
        // ping is itself a liveness deadline and can tear down a valid long-running generation.
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = if (shouldUseResponsesApi(providerSetting, params)) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (shouldUseResponsesApi(providerSetting, params)) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val traceId = traceId(params.traceId)
        val key = selectImageRequestKey(
            providerSetting = providerSetting,
            requiredFingerprint = params.requiredApiKeyFingerprint,
        )
        params.onImageKeySelected(keyFingerprint(key))
        val isGrok = providerSetting.baseUrl.contains("x.ai", ignoreCase = true) ||
            params.model.modelId.contains("grok", ignoreCase = true)
        val requestSize = effectiveImageSize(params.size)
        val responseFormat = if (!isGrok && params.model.modelId.startsWith("gpt-image")) {
            "b64_json"
        } else {
            null
        }

        val mergedRequestBody = buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)

                if (!isGrok) {
                    put("size", requestSize)
                }

                // gpt-image-* is kept inline because the URL path adds a second failure-prone hop.
                // Grok rejects the parameter, and the download fallback still covers other models.
                responseFormat?.let { put("response_format", it) }
            }
            .mergeCustomBody(params.customBody)
        // A stale advanced setting must not silently put gpt-image-* back on the URL path.
        val effectiveRequestBody = if (responseFormat == null) {
            mergedRequestBody
        } else {
            JsonObject(mergedRequestBody + ("response_format" to JsonPrimitive(responseFormat)))
        }
        val requestBody = json.encodeToString(
            effectiveRequestBody
        )

        trace(
            traceId,
            "api_request_start",
            "action=generate model=${params.model.modelId} count=${params.numOfImages} size=$requestSize response_mode=${responseFormat?.let { "${it}_requested" } ?: "provider_default"} transport=fresh_connection prompt_chars=${params.prompt.length}",
        )

        val requestBuilder = Request.Builder()
            .url(
                "${providerSetting.baseUrl}/images/generations" +
                    if (providerSetting.useAsyncImageTasks) "/async" else ""
            )
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
        if (params.idempotencyKey.isNotBlank()) {
            requestBuilder.addHeader("Idempotency-Key", params.idempotencyKey)
        }
        val request = requestBuilder.build()
        val synchronousRequest = request.takeIf { providerSetting.useAsyncImageTasks }
            ?.withoutAsyncImageSuffix()

        val items = withContext(Dispatchers.IO) {
            val apiStartedAt = SystemClock.elapsedRealtime()
            var fellBackToSynchronous = false
            val response = imageRequestClient.newCall(request).await()
            val effectiveResponse = if (
                providerSetting.useAsyncImageTasks &&
                synchronousRequest != null &&
                isAsyncImageEndpointUnsupported(response)
            ) {
                if (!params.allowSynchronousFallback) {
                    response.close()
                    throw ImageGenerationTerminalException(
                        "Cannot safely fall back to synchronous image generation while recovering a durable task"
                    )
                }
                fellBackToSynchronous = true
                trace(
                    traceId,
                    "async_fallback_sync",
                    "action=generate status=${response.code} reason=endpoint_unsupported",
                )
                params.onAsyncFallback()
                response.close()
                imageRequestClient.newCall(synchronousRequest).await()
            } else {
                response
            }
            effectiveResponse.use { response ->
                trace(
                    traceId,
                    "api_response",
                    "action=generate status=${response.code} elapsed_ms=${elapsedSince(apiStartedAt)} fallback_sync=$fellBackToSynchronous",
                )
                if (!response.isSuccessful) {
                    notifyAsyncSubmissionRejected(
                        providerSetting = providerSetting,
                        fellBackToSynchronous = fellBackToSynchronous,
                        statusCode = response.code,
                        correlationId = params.idempotencyKey,
                        onTaskFailed = params.onTaskFailed,
                    )
                    throw imageApiError(
                        action = "generate",
                        response = response,
                        terminal = providerSetting.useAsyncImageTasks &&
                            !fellBackToSynchronous &&
                            response.code in ASYNC_SUBMIT_REJECTION_STATUSES,
                    )
                }
                val bodyStartedAt = SystemClock.elapsedRealtime()
                val bodyStr = response.body.string()
                trace(
                    traceId,
                    "response_body_read",
                    "action=generate chars=${bodyStr.length} elapsed_ms=${elapsedSince(bodyStartedAt)}",
                )
                if (providerSetting.useAsyncImageTasks && !fellBackToSynchronous) {
                    pollSubmittedImageTask(
                        providerSetting = providerSetting,
                        key = key,
                        submitBody = bodyStr,
                        customHeaders = params.customHeaders,
                        traceId = traceId,
                        failureCorrelationId = params.idempotencyKey,
                        onTaskSubmitted = params.onTaskSubmitted,
                        onTaskFailed = params.onTaskFailed,
                    )
                } else {
                    parseImageResponse(
                        bodyStr,
                        traceId,
                        params.idempotencyKey.takeIf(String::isNotBlank) ?: traceId,
                    )
                }
            }
        }

        items.forEach { emit(it) }
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val traceId = traceId(params.traceId)
        val key = selectImageRequestKey(
            providerSetting = providerSetting,
            requiredFingerprint = params.requiredApiKeyFingerprint,
        )
        params.onImageKeySelected(keyFingerprint(key))
        val requestSize = effectiveImageSize(params.size)
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        bodyBuilder.addFormDataPart("size", requestSize)

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }

        val requestBuilder = Request.Builder()
            .url(
                "${providerSetting.baseUrl}/images/edits" +
                    if (providerSetting.useAsyncImageTasks) "/async" else ""
            )
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
        if (params.idempotencyKey.isNotBlank()) {
            requestBuilder.addHeader("Idempotency-Key", params.idempotencyKey)
        }
        val request = requestBuilder.build()
        val synchronousRequest = request.takeIf { providerSetting.useAsyncImageTasks }
            ?.withoutAsyncImageSuffix()

        trace(
            traceId,
            "api_request_start",
            "action=edit model=${params.model.modelId} count=${params.numOfImages} size=$requestSize input_images=${params.images.size} prompt_chars=${params.prompt.length}",
        )
        val items = withContext(Dispatchers.IO) {
            val apiStartedAt = SystemClock.elapsedRealtime()
            var fellBackToSynchronous = false
            val response = imageRequestClient.newCall(request).await()
            val effectiveResponse = if (
                providerSetting.useAsyncImageTasks &&
                synchronousRequest != null &&
                isAsyncImageEndpointUnsupported(response)
            ) {
                if (!params.allowSynchronousFallback) {
                    response.close()
                    throw ImageGenerationTerminalException(
                        "Cannot safely fall back to synchronous image editing while recovering a durable task"
                    )
                }
                fellBackToSynchronous = true
                trace(
                    traceId,
                    "async_fallback_sync",
                    "action=edit status=${response.code} reason=endpoint_unsupported",
                )
                params.onAsyncFallback()
                response.close()
                imageRequestClient.newCall(synchronousRequest).await()
            } else {
                response
            }
            effectiveResponse.use { response ->
                trace(
                    traceId,
                    "api_response",
                    "action=edit status=${response.code} elapsed_ms=${elapsedSince(apiStartedAt)} fallback_sync=$fellBackToSynchronous",
                )
                if (!response.isSuccessful) {
                    notifyAsyncSubmissionRejected(
                        providerSetting = providerSetting,
                        fellBackToSynchronous = fellBackToSynchronous,
                        statusCode = response.code,
                        correlationId = params.idempotencyKey,
                        onTaskFailed = params.onTaskFailed,
                    )
                    throw imageApiError(
                        action = "edit",
                        response = response,
                        terminal = providerSetting.useAsyncImageTasks &&
                            !fellBackToSynchronous &&
                            response.code in ASYNC_SUBMIT_REJECTION_STATUSES,
                    )
                }
                val bodyStartedAt = SystemClock.elapsedRealtime()
                val bodyStr = response.body.string()
                trace(
                    traceId,
                    "response_body_read",
                    "action=edit chars=${bodyStr.length} elapsed_ms=${elapsedSince(bodyStartedAt)}",
                )
                if (providerSetting.useAsyncImageTasks && !fellBackToSynchronous) {
                    pollSubmittedImageTask(
                        providerSetting = providerSetting,
                        key = key,
                        submitBody = bodyStr,
                        customHeaders = params.customHeaders,
                        traceId = traceId,
                        failureCorrelationId = params.idempotencyKey,
                        onTaskSubmitted = params.onTaskSubmitted,
                        onTaskFailed = params.onTaskFailed,
                    )
                } else {
                    parseImageResponse(
                        bodyStr,
                        traceId,
                        params.idempotencyKey.takeIf(String::isNotBlank) ?: traceId,
                    )
                }
            }
        }

        items.forEach { emit(it) }
    }

    override suspend fun resumeImageTask(
        providerSetting: ProviderSetting,
        taskId: String,
        customHeaders: List<me.rerere.ai.provider.CustomHeader>,
        traceId: String,
        apiKeyFingerprint: String,
        onTaskFailed: suspend (String) -> Unit,
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(taskId.isNotBlank()) { "Image task ID cannot be blank" }

        val configuredKeys = keyCandidates(providerSetting.apiKey)
            .ifEmpty { listOf(providerSetting.apiKey) }
        val preferredKey = keyRoulette.findByFingerprint(
            providerSetting.apiKey,
            apiKeyFingerprint,
        )
        val candidates = buildList {
            preferredKey?.let(::add)
            configuredKeys.filterTo(this) { it != preferredKey }
        }
        val requestTraceId = traceId(traceId)
        var lastNotFound: ImageTaskNotFoundException? = null
        for ((index, key) in candidates.withIndex()) {
            try {
                pollImageTask(
                    providerSetting = providerSetting,
                    key = key,
                    taskId = taskId,
                    customHeaders = customHeaders,
                    traceId = requestTraceId,
                    onTaskFailed = onTaskFailed,
                    tryOtherOwnerKeys = true,
                ).forEach { emit(it) }
                return@flow
            } catch (e: ImageTaskNotFoundException) {
                lastNotFound = e
                trace(
                    requestTraceId,
                    "async_task_owner_key_miss",
                    "task_id=$taskId candidate=${index + 1}/${candidates.size}",
                )
            }
        }
        onTaskFailed(taskId)
        throw lastNotFound ?: IllegalStateException("Failed to poll image: task not found")
    }

    private fun selectImageRequestKey(
        providerSetting: ProviderSetting.OpenAI,
        requiredFingerprint: String?,
    ): String {
        if (requiredFingerprint == null) {
            return keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        }
        if (requiredFingerprint.isBlank()) {
            throw ImageGenerationTerminalException(
                "Cannot safely replay an image request without its original API key"
            )
        }
        return keyRoulette.findByFingerprint(providerSetting.apiKey, requiredFingerprint)
            ?: throw ImageGenerationTerminalException(
                "Cannot safely replay an image request because its original API key is no longer configured"
            )
    }

    /**
     * Clear a durable replay record only for statuses that prove submission was rejected before a
     * task could exist. Ambiguous transport failures stay retained because the upstream may already
     * have accepted and billed the request; structured idempotency conflicts are terminal.
     */
    private suspend fun notifyAsyncSubmissionRejected(
        providerSetting: ProviderSetting.OpenAI,
        fellBackToSynchronous: Boolean,
        statusCode: Int,
        correlationId: String,
        onTaskFailed: suspend (String) -> Unit,
    ) {
        if (!providerSetting.useAsyncImageTasks || fellBackToSynchronous ||
            statusCode !in ASYNC_SUBMIT_REJECTION_STATUSES
        ) {
            return
        }
        runCatching { onTaskFailed(correlationId) }
            .onFailure { Log.w(TAG, "Unable to clear rejected async image submission", it) }
    }

    private suspend fun pollSubmittedImageTask(
        providerSetting: ProviderSetting.OpenAI,
        key: String,
        submitBody: String,
        customHeaders: List<me.rerere.ai.provider.CustomHeader>,
        traceId: String,
        failureCorrelationId: String,
        onTaskSubmitted: suspend (String) -> Unit,
        onTaskFailed: suspend (String) -> Unit,
    ): List<ImageGenerationItem> {
        val body = try {
            json.parseToJsonElement(submitBody).jsonObject
        } catch (error: Exception) {
            notifyTaskFailureBestEffort(onTaskFailed, failureCorrelationId)
            throw ImageGenerationTerminalException(
                "Asynchronous image submission returned invalid JSON",
                error,
            )
        }
        val taskId = body["task_id"]?.jsonPrimitive?.contentOrNull
            ?: body["id"]?.jsonPrimitive?.contentOrNull
            ?: run {
                notifyTaskFailureBestEffort(onTaskFailed, failureCorrelationId)
                throw ImageGenerationTerminalException(
                    "Asynchronous image submission did not return a task id",
                )
            }
        if (taskId.isBlank()) {
            notifyTaskFailureBestEffort(onTaskFailed, failureCorrelationId)
            throw ImageGenerationTerminalException(
                "Asynchronous image submission returned an empty task id",
            )
        }
        onTaskSubmitted(taskId)
        trace(traceId, "async_task_submitted", "task_id=$taskId")
        return pollImageTask(
            providerSetting = providerSetting,
            key = key,
            taskId = taskId,
            customHeaders = customHeaders,
            traceId = traceId,
            onTaskFailed = onTaskFailed,
        )
    }

    private suspend fun pollImageTask(
        providerSetting: ProviderSetting.OpenAI,
        key: String,
        taskId: String,
        customHeaders: List<me.rerere.ai.provider.CustomHeader>,
        traceId: String,
        onTaskFailed: suspend (String) -> Unit,
        tryOtherOwnerKeys: Boolean = false,
    ): List<ImageGenerationItem> = withContext(Dispatchers.IO) {
        var pollCount = 0
        while (true) {
            pollCount++
            val request = Request.Builder()
                .url("${providerSetting.baseUrl.trimEnd('/')}/images/tasks/$taskId")
                .headers(customHeaders.toHeaders())
                .addHeader("Authorization", "Bearer $key")
                .get()
                .configureReferHeaders(providerSetting.baseUrl)
                .build()

            val response = try {
                imageRequestClient.newCall(request).await()
            } catch (e: java.io.IOException) {
                trace(
                    traceId,
                    "async_task_poll_retry",
                    "task_id=$taskId poll=$pollCount error=${e.javaClass.simpleName}",
                )
                delay(imageTaskPollIntervalMillis)
                continue
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code >= 500 || resp.code == 408 || resp.code == 429) {
                        delay(imageTaskPollIntervalMillis)
                        return@use
                    }
                    if (resp.code == 410) {
                        // Sub2 uses 410 for a terminally expired result. Retaining the local
                        // replay record here would make every recovery wake poll the same dead
                        // task forever, while replaying the original POST is unsafe because the
                        // provider may already have charged it.
                        val detail = imageApiError("poll", resp).message
                            ?.substringAfter(": ")
                            ?.takeIf(String::isNotBlank)
                            ?: "IMAGE_TASK_RESULT_EXPIRED"
                        // A callback failure must not turn a terminal upstream result into an
                        // apparently retryable transport failure. The durable record is best-effort
                        // cleanup; the explicit terminal exception remains the source of truth.
                        try {
                            onTaskFailed(taskId)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (cleanupError: Throwable) {
                            Log.w(TAG, "Unable to clear expired image task $taskId", cleanupError)
                        }
                        throw ImageTaskResultExpiredException(taskId, detail)
                    }
                    if (resp.code == 404) {
                        val error = imageApiError("poll", resp)
                        if (tryOtherOwnerKeys) {
                            throw ImageTaskNotFoundException(error.message ?: "Image task not found")
                        }
                        notifyTaskFailureBestEffort(onTaskFailed, taskId)
                        throw ImageTaskNotFoundException(error.message ?: "Image task not found")
                    }
                    notifyTaskFailureBestEffort(onTaskFailed, taskId)
                    throw imageApiError("poll", resp, terminal = true)
                }

                val bodyStr = resp.body.string()
                val body = try {
                    json.parseToJsonElement(bodyStr).jsonObject
                } catch (error: Exception) {
                    notifyTaskFailureBestEffort(onTaskFailed, taskId)
                    throw ImageGenerationTerminalException(
                        "Image task returned invalid JSON (task=$taskId)",
                        error,
                    )
                }
                val status = body["status"]?.jsonPrimitive?.contentOrNull
                when (status) {
                "processing" -> {
                    trace(traceId, "async_task_processing", "task_id=$taskId poll=$pollCount")
                    delay(imageTaskPollIntervalMillis)
                }

                "completed" -> {
                    val result = body["result"] ?: run {
                        notifyTaskFailureBestEffort(onTaskFailed, taskId)
                        throw ImageGenerationTerminalException(
                            "Completed image task has no result (task=$taskId)",
                        )
                    }
                    trace(
                        traceId,
                        "async_task_completed",
                        "task_id=$taskId polls=$pollCount",
                    )
                    try {
                        return@withContext parseImageResponse(result.toString(), traceId, taskId)
                    } catch (e: java.io.IOException) {
                        // The server result is durable. If only the object-storage download fails,
                        // poll the same completed task again instead of replaying image generation.
                        trace(
                            traceId,
                            "async_result_download_retry",
                            "task_id=$taskId error=${e.javaClass.simpleName}",
                        )
                        delay(imageTaskPollIntervalMillis)
                    } catch (e: ImageGenerationTerminalException) {
                        notifyTaskFailureBestEffort(onTaskFailed, taskId)
                        throw e
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        notifyTaskFailureBestEffort(onTaskFailed, taskId)
                        throw ImageGenerationTerminalException(
                            "Completed image task returned an invalid result (task=$taskId)",
                            e,
                        )
                    }
                }

                "failed" -> {
                    notifyTaskFailureBestEffort(onTaskFailed, taskId)
                    val detail = (body["error"] as? JsonObject)
                        ?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "Image generation task failed"
                    throw ImageGenerationTerminalException("Failed to generate image: $detail")
                }

                else -> {
                    notifyTaskFailureBestEffort(onTaskFailed, taskId)
                    throw ImageGenerationTerminalException(
                        "Unknown asynchronous image task status${status?.let { " '$it'" }.orEmpty()} (task=$taskId)",
                    )
                }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("image task polling loop exited unexpectedly")
    }

    private suspend fun notifyTaskFailureBestEffort(
        onTaskFailed: suspend (String) -> Unit,
        taskId: String,
    ) {
        if (taskId.isBlank()) return
        try {
            onTaskFailed(taskId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (cleanupError: Throwable) {
            Log.w(TAG, "Unable to clear terminal image task $taskId", cleanupError)
        }
    }

    /**
     * Preserve a structured gateway error message when available, but never put an arbitrary
     * response body in an exception. Gateways can echo prompts or credentials in HTML/text errors.
     */
    private fun isAsyncImageEndpointUnsupported(response: Response): Boolean {
        // A method-not-allowed or not-implemented response is produced before a request can reach
        // image generation.  Do not include 408/429/5xx here: those responses can arrive after an
        // upstream account has accepted and billed the request.
        if (response.code == 405 || response.code == 501) return true
        if (response.header(ASYNC_IMAGE_UNSUPPORTED_HEADER)
                ?.equals(ASYNC_IMAGE_UNSUPPORTED_VALUE, ignoreCase = true) == true
        ) {
            return true
        }
        if (response.code != 404) return false

        // Older Sub2 deployments predate the capability header.  Keep a narrow compatibility
        // check for their exact disabled message; a generic 404 (proxy, model, or auth failure)
        // must remain terminal rather than risking a second billable request.
        return runCatching {
            val root = json.parseToJsonElement(
                response.peekBody(ASYNC_IMAGE_ERROR_PEEK_BYTES).string()
            ) as? JsonObject
            val error = root?.get("error") as? JsonObject
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            message?.trim()?.equals(LEGACY_ASYNC_DISABLED_MESSAGE, ignoreCase = true) == true
        }.getOrDefault(false)
    }

    private fun Request.withoutAsyncImageSuffix(): Request {
        val path = url.encodedPath
        if (!path.endsWith("/async")) return this
        val synchronousUrl = url.newBuilder()
            .encodedPath(path.removeSuffix("/async"))
            .build()
        return newBuilder().url(synchronousUrl).build()
    }

    private fun imageApiError(action: String, response: Response, terminal: Boolean = false): Throwable {
        val bodyStr = response.body.string()
        val parsedBody = runCatching { json.parseToJsonElement(bodyStr) }.getOrNull()
        val detail = runCatching { parsedBody?.parseErrorDetail()?.message }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
            ?.take(500)
            ?: "HTTP ${response.code}"
        val errorCode = ((parsedBody as? JsonObject)?.get("error") as? JsonObject)
            ?.get("code")
            ?.jsonPrimitive
            ?.contentOrNull
        val message = "Failed to $action image: $detail"
        return if (terminal || errorCode in TERMINAL_IMAGE_TASK_ERROR_CODES) {
            ImageGenerationTerminalException(message)
        } else {
            IllegalStateException(message)
        }
    }

    /**
     * n>1 时多张图各自是一个远端 URL, 逐个串行下载会把等待时间直接乘以张数, 所以并发下.
     */
    internal suspend fun parseImageResponse(
        bodyStr: String,
        requestTraceId: String = "",
        downloadKey: String = requestTraceId,
    ): List<ImageGenerationItem> = coroutineScope {
        val traceId = traceId(requestTraceId)
        val parseStartedAt = SystemClock.elapsedRealtime()
        val body = try {
            json.parseToJsonElement(bodyStr).jsonObject
        } catch (error: Exception) {
            throw ImageGenerationTerminalException("Image response was not valid JSON", error)
        }
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.let { element ->
            try {
                element.jsonArray
            } catch (error: Exception) {
                throw ImageGenerationTerminalException("Image response data was not an array", error)
            }
        } ?: throw ImageGenerationTerminalException("No data in image response")
        if (data.isEmpty()) {
            throw ImageGenerationTerminalException("Image response data was empty")
        }
        var inlineBase64Count = 0
        var dataUriCount = 0
        var remoteUrlCount = 0
        val deferredItems = data.mapIndexed { index, element ->
            val obj = try {
                element.jsonObject
            } catch (error: Exception) {
                throw ImageGenerationTerminalException("Image response item was malformed", error)
            }
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (!b64Json.isNullOrBlank()) {
                inlineBase64Count++
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                CompletableDeferred(
                    ImageGenerationItem(
                        data = b64Json,
                        mimeType = outputFormat.toImageMimeType(),
                    )
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: throw ImageGenerationTerminalException("Image response item had no b64_json or url image data")
                // 网关有时把整张图塞进 data URI 而不是给一个可下载的地址. OkHttp 不认 data: 这个
                // scheme, 直接构造 Request 会抛 IllegalArgumentException, 所以这里当内联数据处理.
                if (url.startsWith("data:")) {
                    val base64Marker = "base64,"
                    val base64Start = url.indexOf(base64Marker)
                    if (base64Start < 0 || url.substring(base64Start + base64Marker.length).isBlank()) {
                        throw ImageGenerationTerminalException("Image response data URI was empty or malformed")
                    }
                    dataUriCount++
                    val mimeType = url.substringAfter("data:").substringBefore(";")
                        .ifBlank { defaultFormat.toImageMimeType() }
                    CompletableDeferred(
                        ImageGenerationItem(
                            data = url.substring(base64Start + base64Marker.length),
                            mimeType = mimeType,
                        )
                    )
                } else {
                    remoteUrlCount++
                    async { downloadImageToFile(url, traceId, index, downloadKey) }
                }
            }
        }
        trace(
            traceId,
            "response_parsed",
            "items=${data.size} inline_b64=$inlineBase64Count data_uri=$dataUriCount remote_url=$remoteUrlCount elapsed_ms=${elapsedSince(parseStartedAt)}",
        )
        val items = deferredItems.map { it.await() }
        trace(traceId, "response_items_ready", "items=${items.size} elapsed_ms=${elapsedSince(parseStartedAt)}")
        items
    }

    /**
     * Image URLs are downloaded without a total or idle-read deadline. A transport failure or
     * process restart resumes from the durable `.part` file, so a slow CDN cannot turn a transient
     * connection problem into a second billable image request.
     */
    private val imageDownloadClient by lazy {
        client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(0, TimeUnit.MILLISECONDS)
            // Do not turn a slow CDN into a false image-generation failure. A transport error,
            // coroutine cancellation, or a process restart still leaves the durable .part file
            // available for the next Range request.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 下载落地目录。恢复记录和 `.part/.meta` 必须放在应用持久目录，不能放在
     * `cacheDir`：Android 可以在后台或低存储时无通知清空 cache，进而破坏断点续传。
     * 拿不到 context 时才退回 JVM 临时目录（仅 JVM 单测/非 Android 宿主）。
     * 进程被杀会留下没搬走的 `.part` 文件; 它们必须保留到同一 task 的下一次轮询.
     */
    private val downloadStoreDir: File by lazy {
        val dir = appContext?.filesDir?.resolve("imggen_download")
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        dir.mkdirs()
        dir
    }

    /**
     * 直接流式写入临时文件, 不经过 base64: 之前是 bytes() 全量读进内存 -> 编码成 base64 字符串
     * -> 消费方再解码写盘, 一张 3MB 的图要额外分配十几 MB 并多走两遍拷贝.
     *
     * 下载失败可以放心重试: 图已经生成、费用已经产生, 重下一次不会再计费.
     * (生成请求本身按次计费, 所以那一层不能这样重试)
     */
    private suspend fun downloadImageToFile(
        url: String,
        traceId: String,
        index: Int,
        downloadKey: String,
    ): ImageGenerationItem =
        withContext(Dispatchers.IO) {
            // The task/download key is the durable identity for this result. Never include a
            // presigned URL (even its path) in the checkpoint name: gateways may rotate the URL
            // between polls, while the same task and item index must keep using the same .part.
            // Legacy callers can omit a key; in that case use the URL with its query removed as a
            // deterministic compatibility fallback.
            val stableName = sha256(downloadCheckpointIdentity(downloadKey, index, url))
            val partFile = File(downloadStoreDir, "imggen_dl_$stableName.part")
            val metadataFile = File(downloadStoreDir, "imggen_dl_$stableName.meta")
            var metadata = readDownloadMetadata(metadataFile)
            if (
                metadata.complete &&
                (metadata.totalLength == null || metadata.totalLength != partFile.length())
            ) {
                metadata = metadata.copy(complete = false)
            }
            if (
                partFile.length() > 0L &&
                (
                    metadata.url.isNullOrBlank() ||
                        (!metadata.complete && metadata.validator.isNullOrBlank())
                    )
            ) {
                // A byte offset alone cannot prove that the next response belongs to the same
                // object. Corrupt/missing metadata or an origin without a validator must restart
                // from zero instead of risking a mixed image.
                resetDownload(partFile, metadataFile)
                metadata = DownloadMetadata()
            }
            if (metadata.url != null && downloadObjectIdentity(metadata.url) != downloadObjectIdentity(url)) {
                resetDownload(partFile, metadataFile)
                metadata = DownloadMetadata()
            }
            metadata = metadata.copy(url = url)
            if (
                metadata.complete &&
                partFile.length() > 0L &&
                metadata.totalLength == partFile.length()
            ) {
                // Keep the latest signed URL in the sidecar even when no network request is
                // needed. The next process can then compare object identity without treating a
                // rotated query string as a new object.
                writeDownloadMetadata(metadataFile, metadata)
                return@withContext ImageGenerationItem(
                    mimeType = metadata.mimeType ?: "image/png",
                    localPath = partFile.absolutePath,
                )
            }
            var lastError: Exception? = null
            repeat(IMAGE_DOWNLOAD_ATTEMPTS) { attempt ->
                try {
                    val downloadStartedAt = SystemClock.elapsedRealtime()
                    trace(traceId, "download_start", "index=$index attempt=${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS")
                    val offset = partFile.length()
                    val requestBuilder = Request.Builder().url(url).get()
                    if (offset > 0L) {
                        requestBuilder.addHeader("Range", "bytes=$offset-")
                        metadata.validator?.takeIf(String::isNotBlank)?.let {
                            requestBuilder.addHeader("If-Range", it)
                        }
                    }
                    val response = imageDownloadClient.newCall(requestBuilder.build()).await()
                    response.use { resp ->
                        trace(
                            traceId,
                            "download_response",
                            "index=$index attempt=${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS status=${resp.code} elapsed_ms=${elapsedSince(downloadStartedAt)}",
                        )
                        if (resp.code == 416) {
                            val total = parseContentRangeTotal(resp.header("Content-Range"))
                            if (total != null && total == offset && offset > 0L) {
                                metadata = metadata.copy(
                                    complete = true,
                                    totalLength = total,
                                    mimeType = metadata.mimeType ?: "image/png",
                                )
                                writeDownloadMetadata(metadataFile, metadata)
                                return@withContext ImageGenerationItem(
                                    mimeType = metadata.mimeType ?: "image/png",
                                    localPath = partFile.absolutePath,
                                )
                            }
                            resetDownload(partFile, metadataFile)
                            metadata = metadata.copy(complete = false, totalLength = null)
                            error("Generated image range is no longer valid")
                        }
                        if (!resp.isSuccessful) {
                            error("Failed to download generated image: ${resp.code}")
                        }

                        val isPartial = resp.code == 206
                        val append = offset > 0L && isPartial
                        val responseValidator = resp.header("ETag")?.takeIf(String::isNotBlank)
                            ?: resp.header("Last-Modified")?.takeIf(String::isNotBlank)
                        if (
                            isPartial &&
                            metadata.validator != null &&
                            responseValidator != null &&
                            metadata.validator != responseValidator
                        ) {
                            // A broken origin may return 206 despite an entity change. Never
                            // append bytes from a different object; restart from byte zero.
                            resetDownload(partFile, metadataFile)
                            metadata = DownloadMetadata(url = url)
                            error("Generated image entity changed during resume")
                        }
                        if (isPartial) {
                            val range = parseContentRange(resp.header("Content-Range"))
                            if (range == null || range.first != offset) {
                                resetDownload(partFile, metadataFile)
                                metadata = metadata.copy(complete = false, totalLength = null)
                                error("Generated image range did not start at $offset")
                            }
                        }
                        // A 200 response means the origin ignored Range. Restart safely from byte 0.
                        val contentType = resp.body.contentType()?.toString()?.takeIf(String::isNotBlank)
                            ?: metadata.mimeType
                            ?: "image/png"
                        val range = if (isPartial) parseContentRange(resp.header("Content-Range")) else null
                        val expectedLength = when {
                            range?.second != null -> range.second
                            resp.body.contentLength() >= 0L ->
                                (if (append) offset else 0L) + resp.body.contentLength()
                            else -> null
                        }
                        if (offset > 0L && !isPartial) {
                            // The origin ignored Range (usually because If-Range detected a new
                            // entity). Remove the old prefix durably before publishing metadata
                            // for the replacement. Otherwise a power loss between the metadata
                            // rename and part-file truncation could pair old bytes with the new
                            // validator and corrupt the next 206 append.
                            resetDownload(partFile, metadataFile)
                            metadata = DownloadMetadata(url = url)
                        }
                        // Persist the entity validator before consuming the body. If Android kills
                        // the process mid-stream, the next process can safely resume with If-Range.
                        metadata = metadata.copy(
                            // A 200 response restarts the entity, so never carry an old
                            // validator into a new object. A 206 continuation may reuse the
                            // validator from the first response when the range response omits it.
                            validator = if (append) responseValidator ?: metadata.validator else responseValidator,
                            mimeType = contentType,
                            totalLength = expectedLength,
                            complete = false,
                        )
                        writeDownloadMetadata(metadataFile, metadata)

                        FileOutputStream(partFile, append).use { output ->
                            try {
                                resp.body.byteStream().use { input -> input.copyTo(output) }
                            } finally {
                                // Sync even an interrupted prefix. The next process derives its
                                // Range offset from the durable file length, not buffered bytes.
                                output.flush()
                                output.fd.sync()
                            }
                        }
                        if (partFile.length() == 0L) {
                            partFile.delete()
                            error("Downloaded image is empty")
                        }

                        if (expectedLength != null && partFile.length() != expectedLength) {
                            val direction = if (partFile.length() < expectedLength) "before" else "after"
                            error("Generated image download ended $direction byte $expectedLength")
                        }
                        val complete = expectedLength == null || partFile.length() >= expectedLength
                        metadata = metadata.copy(
                            totalLength = expectedLength ?: partFile.length(),
                            complete = complete,
                        )
                        writeDownloadMetadata(metadataFile, metadata)
                        if (!complete) error("Generated image download is incomplete")
                        trace(
                            traceId,
                            "download_complete", "index=$index attempt=${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS " +
                                "bytes=${partFile.length()} elapsed_ms=${elapsedSince(downloadStartedAt)}",
                        )
                        return@withContext ImageGenerationItem(
                            mimeType = contentType,
                            localPath = partFile.absolutePath,
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Keep the partial file. WorkManager or the next poll will resume it.
                    throw e
                } catch (e: IllegalArgumentException) {
                    // A malformed object URL is deterministic. Re-polling the same completed
                    // task cannot repair it and must not keep a durable task alive forever.
                    throw ImageGenerationTerminalException(
                        "Generated image URL was invalid",
                        e,
                    )
                } catch (e: Exception) {
                    lastError = e
                    trace(
                        traceId,
                        "download_failed",
                        "index=$index attempt=${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS error=${e.javaClass.simpleName}",
                    )
                    Log.w(TAG, "Image download failed (attempt ${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS)", e)
                    if (partFile.length() == 0L) {
                        resetDownload(partFile, metadataFile)
                        metadata = DownloadMetadata(url = url)
                    }
                    if (attempt < IMAGE_DOWNLOAD_ATTEMPTS - 1) {
                        kotlinx.coroutines.delay(IMAGE_DOWNLOAD_RETRY_DELAY_MS shl attempt)
                    }
                }
            }
            val cause = lastError ?: java.io.IOException("download failed without a cause")
            // Keep download errors retryable by async task polling, but do not expose the original
            // transport subtype to the manager. Otherwise an UnknownHostException from the CDN
            // could be mistaken for a pre-submit DNS failure and replay the billable image POST.
            throw ImageResultDownloadException(cause)
        }

    private class ImageResultDownloadException(cause: Throwable) : java.io.IOException(
        "Failed to download generated image: ${cause.message ?: cause.javaClass.simpleName}",
        cause,
    )

    private data class DownloadMetadata(
        val url: String? = null,
        val validator: String? = null,
        val mimeType: String? = null,
        val totalLength: Long? = null,
        val complete: Boolean = false,
    )

    private fun readDownloadMetadata(file: File): DownloadMetadata {
        if (!file.isFile) return DownloadMetadata()
        return runCatching {
            Properties().apply { file.inputStream().use(::load) }.let { props ->
                DownloadMetadata(
                    url = props.getProperty("url"),
                    validator = props.getProperty("validator"),
                    mimeType = props.getProperty("mime_type"),
                    totalLength = props.getProperty("total_length")?.toLongOrNull(),
                    complete = props.getProperty("complete") == "true",
                )
            }
        }.getOrDefault(DownloadMetadata())
    }

    private fun writeDownloadMetadata(file: File, metadata: DownloadMetadata) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        val props = Properties().apply {
            metadata.url?.let { setProperty("url", it) }
            metadata.validator?.let { setProperty("validator", it) }
            metadata.mimeType?.let { setProperty("mime_type", it) }
            metadata.totalLength?.let { setProperty("total_length", it.toString()) }
            setProperty("complete", metadata.complete.toString())
        }
        var installed = false
        try {
            FileOutputStream(temp).use { output ->
                props.store(output, null)
                output.flush()
                output.fd.sync()
            }
            try {
                try {
                    Files.move(
                        temp.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: UnsupportedOperationException) {
                    Files.move(
                        temp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (moveFailure: Exception) {
                // Some Android filesystems reject NIO moves even though same-directory rename
                // works. Preserve the old sidecar if that fallback also fails; never stream the
                // temp bytes directly into `file`.
                if (!temp.renameTo(file)) {
                    throw IOException("Unable to atomically install image download metadata", moveFailure)
                }
            }
            installed = true
        } finally {
            if (!installed) temp.delete()
        }
        // The metadata bytes are synced before rename; sync the directory entry where the
        // filesystem permits it. Some Android providers reject opening directories, so this
        // is deliberately best effort.
        syncDownloadDirectory(file.parentFile)
    }

    private fun resetDownload(part: File, metadata: File) {
        if (part.exists() && !part.delete() && part.exists()) {
            throw IOException("Unable to discard stale image download prefix: ${part.absolutePath}")
        }
        metadata.delete()
        File(metadata.parentFile, "${metadata.name}.tmp").delete()
        syncDownloadDirectory(metadata.parentFile)
    }

    private fun syncDownloadDirectory(directory: File?) {
        runCatching {
            directory?.let { FileInputStream(it).use { input -> input.fd.sync() } }
        }
    }

    private fun parseContentRange(value: String?): Pair<Long, Long?>? {
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).matchEntire(value?.trim().orEmpty())
            ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        return start to match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    }

    private fun parseContentRangeTotal(value: String?): Long? {
        val match = Regex("bytes\\s+\\*/(\\d+)", RegexOption.IGNORE_CASE).matchEntire(value?.trim().orEmpty())
        return match?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    /**
     * Returns the stable object identity for a download URL. Query parameters are intentionally
     * excluded because private object stores rotate presigned signatures; scheme/authority/path
     * changes still identify a different object and must reset a partial download.
     */
    private fun downloadObjectIdentity(url: String): String = runCatching {
        val parsed = URI(url)
        URI(parsed.scheme, parsed.rawAuthority, parsed.rawPath, null, null).toString()
    }.getOrElse {
        // Keep malformed/opaque URLs deterministic without accidentally treating a changed path
        // as the same object. This fallback is only for non-standard test or gateway URLs.
        url.substringBefore('#').substringBefore('?')
    }

    private fun downloadCheckpointIdentity(downloadKey: String, index: Int, url: String): String {
        val key = downloadKey.trim()
        return if (key.isNotEmpty()) "$key|$index" else "${downloadObjectIdentity(url)}|$index"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private const val TRACE_TAG = "ImgGenTrace"
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        private const val IMAGE_DOWNLOAD_ATTEMPTS = 3
        private const val IMAGE_DOWNLOAD_RETRY_DELAY_MS = 500L
        private const val CHAT_HTTP2_PING_INTERVAL_SECONDS = 15L
        private const val IMAGE_TASK_POLL_INTERVAL_MS = 3_000L
        private const val ASYNC_IMAGE_UNSUPPORTED_HEADER = "X-Sub2-Async-Image"
        private const val ASYNC_IMAGE_UNSUPPORTED_VALUE = "unsupported"
        private const val LEGACY_ASYNC_DISABLED_MESSAGE = "async image tasks are not enabled"
        private const val ASYNC_IMAGE_ERROR_PEEK_BYTES = 16 * 1024L
        private val ASYNC_SUBMIT_REJECTION_STATUSES = setOf(400, 401, 403, 410, 413, 415, 422)
        private val TERMINAL_IMAGE_TASK_ERROR_CODES = setOf("IMAGE_TASK_IDEMPOTENCY_CONFLICT")

        private fun traceId(value: String): String = value.ifBlank { "provider-untracked" }

        private fun elapsedSince(startedAt: Long): Long = SystemClock.elapsedRealtime() - startedAt

        private fun trace(traceId: String, stage: String, details: String) {
            Log.i(TRACE_TAG, "trace=$traceId stage=$stage $details")
        }
    }
}

internal fun shouldUseResponsesApi(
    providerSetting: ProviderSetting.OpenAI,
    params: TextGenerationParams,
): Boolean = providerSetting.useResponseApi || params.model.tools.isNotEmpty()

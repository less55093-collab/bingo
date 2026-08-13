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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
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
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAIProvider"

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
        .pingInterval(IMAGE_HTTP2_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
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
    ): Flow<MessageChunk> = if (providerSetting.useResponseApi) {
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
    ): MessageChunk = if (providerSetting.useResponseApi) {
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
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val isGrok = providerSetting.baseUrl.contains("x.ai", ignoreCase = true) ||
            params.model.modelId.contains("grok", ignoreCase = true)
        val responseFormat = if (!isGrok && params.model.modelId.startsWith("gpt-image")) {
            "b64_json"
        } else {
            null
        }

        val mergedRequestBody = buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)

                if (params.size.isNotBlank() && !isGrok) {
                    put("size", params.size)
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
            "action=generate model=${params.model.modelId} count=${params.numOfImages} size=${params.size} response_mode=${responseFormat?.let { "${it}_requested" } ?: "provider_default"} transport=fresh_connection prompt_chars=${params.prompt.length}",
        )

        val request = Request.Builder()
            .url(
                "${providerSetting.baseUrl}/images/generations" +
                    if (providerSetting.useAsyncImageTasks) "/async" else ""
            )
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val apiStartedAt = SystemClock.elapsedRealtime()
            val response = imageRequestClient.newCall(request).await()
            trace(
                traceId,
                "api_response",
                "action=generate status=${response.code} elapsed_ms=${elapsedSince(apiStartedAt)}",
            )
            if (!response.isSuccessful) {
                throw imageApiError("generate", response)
            }
            val bodyStartedAt = SystemClock.elapsedRealtime()
            val bodyStr = response.body.string()
            trace(
                traceId,
                "response_body_read",
                "action=generate chars=${bodyStr.length} elapsed_ms=${elapsedSince(bodyStartedAt)}",
            )
            if (providerSetting.useAsyncImageTasks) {
                pollSubmittedImageTask(
                    providerSetting = providerSetting,
                    key = key,
                    submitBody = bodyStr,
                    customHeaders = params.customHeaders,
                    traceId = traceId,
                    onTaskSubmitted = params.onTaskSubmitted,
                    onTaskFailed = params.onTaskFailed,
                )
            } else {
                parseImageResponse(bodyStr, traceId)
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
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        if (params.size.isNotBlank()) {
            bodyBuilder.addFormDataPart("size", params.size)
        }

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

        val request = Request.Builder()
            .url(
                "${providerSetting.baseUrl}/images/edits" +
                    if (providerSetting.useAsyncImageTasks) "/async" else ""
            )
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        trace(
            traceId,
            "api_request_start",
            "action=edit model=${params.model.modelId} count=${params.numOfImages} size=${params.size} input_images=${params.images.size} prompt_chars=${params.prompt.length}",
        )
        val items = withContext(Dispatchers.IO) {
            val apiStartedAt = SystemClock.elapsedRealtime()
            val response = imageRequestClient.newCall(request).await()
            trace(
                traceId,
                "api_response",
                "action=edit status=${response.code} elapsed_ms=${elapsedSince(apiStartedAt)}",
            )
            if (!response.isSuccessful) {
                throw imageApiError("edit", response)
            }
            val bodyStartedAt = SystemClock.elapsedRealtime()
            val bodyStr = response.body.string()
            trace(
                traceId,
                "response_body_read",
                "action=edit chars=${bodyStr.length} elapsed_ms=${elapsedSince(bodyStartedAt)}",
            )
            if (providerSetting.useAsyncImageTasks) {
                pollSubmittedImageTask(
                    providerSetting = providerSetting,
                    key = key,
                    submitBody = bodyStr,
                    customHeaders = params.customHeaders,
                    traceId = traceId,
                    onTaskSubmitted = params.onTaskSubmitted,
                    onTaskFailed = params.onTaskFailed,
                )
            } else {
                parseImageResponse(bodyStr, traceId)
            }
        }

        items.forEach { emit(it) }
    }

    override suspend fun resumeImageTask(
        providerSetting: ProviderSetting,
        taskId: String,
        customHeaders: List<me.rerere.ai.provider.CustomHeader>,
        traceId: String,
        onTaskFailed: suspend (String) -> Unit,
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(providerSetting.useAsyncImageTasks) {
            "Asynchronous image tasks are not enabled for this provider"
        }
        require(taskId.isNotBlank()) { "Image task ID cannot be blank" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        pollImageTask(
            providerSetting = providerSetting,
            key = key,
            taskId = taskId,
            customHeaders = customHeaders,
            traceId = traceId(traceId),
            onTaskFailed = onTaskFailed,
        ).forEach { emit(it) }
    }

    private suspend fun pollSubmittedImageTask(
        providerSetting: ProviderSetting.OpenAI,
        key: String,
        submitBody: String,
        customHeaders: List<me.rerere.ai.provider.CustomHeader>,
        traceId: String,
        onTaskSubmitted: suspend (String) -> Unit,
        onTaskFailed: suspend (String) -> Unit,
    ): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(submitBody).jsonObject
        val taskId = body["task_id"]?.jsonPrimitive?.contentOrNull
            ?: body["id"]?.jsonPrimitive?.contentOrNull
            ?: error("No task_id in asynchronous image response")
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
    ): List<ImageGenerationItem> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        var pollCount = 0
        while (elapsedSince(startedAt) < IMAGE_TASK_POLL_TIMEOUT_MS) {
            pollCount++
            val request = Request.Builder()
                .url("${providerSetting.baseUrl.trimEnd('/')}/images/tasks/$taskId")
                .headers(customHeaders.toHeaders())
                .addHeader("Authorization", "Bearer $key")
                .get()
                .configureReferHeaders(providerSetting.baseUrl)
                .build()

            val response = try {
                client.newCall(request).await()
            } catch (e: java.io.IOException) {
                trace(
                    traceId,
                    "async_task_poll_retry",
                    "task_id=$taskId poll=$pollCount error=${e.javaClass.simpleName}",
                )
                delay(imageTaskPollIntervalMillis)
                continue
            }
            if (!response.isSuccessful) {
                if (response.code >= 500 || response.code == 408 || response.code == 429) {
                    response.close()
                    delay(imageTaskPollIntervalMillis)
                    continue
                }
                if (response.code == 404) {
                    onTaskFailed(taskId)
                }
                throw imageApiError("poll", response)
            }

            val bodyStr = response.body.string()
            val body = json.parseToJsonElement(bodyStr).jsonObject
            when (body["status"]?.jsonPrimitive?.contentOrNull) {
                "processing" -> {
                    trace(traceId, "async_task_processing", "task_id=$taskId poll=$pollCount")
                    delay(imageTaskPollIntervalMillis)
                }

                "completed" -> {
                    val result = body["result"] ?: error("No result in completed image task")
                    trace(
                        traceId,
                        "async_task_completed",
                        "task_id=$taskId polls=$pollCount elapsed_ms=${elapsedSince(startedAt)}",
                    )
                    try {
                        return@withContext parseImageResponse(result.toString(), traceId)
                    } catch (e: java.io.IOException) {
                        // The server result is durable. If only the object-storage download fails,
                        // poll the same completed task again instead of replaying image generation.
                        trace(
                            traceId,
                            "async_result_download_retry",
                            "task_id=$taskId error=${e.javaClass.simpleName}",
                        )
                        delay(imageTaskPollIntervalMillis)
                    }
                }

                "failed" -> {
                    onTaskFailed(taskId)
                    val detail = (body["error"] as? JsonObject)
                        ?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "Image generation task failed"
                    throw IllegalStateException("Failed to generate image: $detail")
                }

                else -> error("Unknown asynchronous image task status")
            }
        }
        throw java.net.SocketTimeoutException("Timed out waiting for image generation task")
    }

    /**
     * Preserve a structured gateway error message when available, but never put an arbitrary
     * response body in an exception. Gateways can echo prompts or credentials in HTML/text errors.
     */
    private fun imageApiError(action: String, response: Response): Throwable {
        val bodyStr = response.body.string()
        val detail = runCatching { json.parseToJsonElement(bodyStr).parseErrorDetail().message }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it.trimStart().startsWith("{") || it.trimStart().startsWith("[") }
            ?.take(500)
            ?: "HTTP ${response.code}"
        return IllegalStateException("Failed to $action image: $detail")
    }

    /**
     * n>1 时多张图各自是一个远端 URL, 逐个串行下载会把等待时间直接乘以张数, 所以并发下.
     */
    internal suspend fun parseImageResponse(
        bodyStr: String,
        requestTraceId: String = "",
    ): List<ImageGenerationItem> = coroutineScope {
        val traceId = traceId(requestTraceId)
        val parseStartedAt = SystemClock.elapsedRealtime()
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        var inlineBase64Count = 0
        var dataUriCount = 0
        var remoteUrlCount = 0
        val deferredItems = data.mapIndexed { index, element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                inlineBase64Count++
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                CompletableDeferred(
                    ImageGenerationItem(
                        data = b64Json,
                        mimeType = outputFormat.toImageMimeType(),
                    )
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                // 网关有时把整张图塞进 data URI 而不是给一个可下载的地址. OkHttp 不认 data: 这个
                // scheme, 直接构造 Request 会抛 IllegalArgumentException, 所以这里当内联数据处理.
                if (url.startsWith("data:")) {
                    dataUriCount++
                    val mimeType = url.substringAfter("data:").substringBefore(";")
                        .ifBlank { defaultFormat.toImageMimeType() }
                    CompletableDeferred(
                        ImageGenerationItem(
                            data = url.substringAfter("base64,"),
                            mimeType = mimeType,
                        )
                    )
                } else {
                    remoteUrlCount++
                    async { downloadImageToFile(url, traceId, index) }
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
     * 网关返回的是远端 URL 而不是 base64, 所以出图后还要再下一次图.
     * 这一步用的是聊天用的 client, 它的 readTimeout 是 10 分钟(为了 SSE 长连接);
     * 图片 CDN 一旦断流, 这里就会挂满 10 分钟, 表现为"上游早就出图并计了费, app 还在转圈".
     * 因此单独给下载套一个 callTimeout 上限, 让它尽快失败并交给下面的重试.
     */
    private val imageDownloadClient by lazy {
        client.newBuilder()
            .callTimeout(IMAGE_DOWNLOAD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载落地目录; 拿不到 context 时退回 JVM 临时目录, 不因此让生图失败.
     * 进程被杀会留下没搬走的临时文件, 所以首次用到时清一次上一轮的残留.
     */
    private val downloadCacheDir: File by lazy {
        val dir = appContext?.cacheDir?.resolve("imggen_download")
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        dir.mkdirs()
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith("imggen_dl_") }
                ?.forEach { it.delete() }
        }
        dir
    }

    /**
     * 直接流式写入临时文件, 不经过 base64: 之前是 bytes() 全量读进内存 -> 编码成 base64 字符串
     * -> 消费方再解码写盘, 一张 3MB 的图要额外分配十几 MB 并多走两遍拷贝.
     *
     * 下载失败可以放心重试: 图已经生成、费用已经产生, 重下一次不会再计费.
     * (生成请求本身按次计费, 所以那一层不能这样重试)
     */
    private suspend fun downloadImageToFile(url: String, traceId: String, index: Int): ImageGenerationItem =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            var lastError: Exception? = null
            repeat(IMAGE_DOWNLOAD_ATTEMPTS) { attempt ->
                var target: File? = null
                try {
                    val downloadStartedAt = SystemClock.elapsedRealtime()
                    trace(traceId, "download_start", "index=$index attempt=${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS")
                    val response = imageDownloadClient.newCall(request).await()
                    response.use { resp ->
                        trace(
                            traceId,
                            "download_response",
                            "index=$index attempt=${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS status=${resp.code} elapsed_ms=${elapsedSince(downloadStartedAt)}",
                        )
                        if (!resp.isSuccessful) {
                            error("Failed to download generated image: ${resp.code}")
                        }
                        val body = resp.body
                        val mimeType = body.contentType()?.toString() ?: "image/png"
                        val file = File.createTempFile("imggen_dl_", ".tmp", downloadCacheDir)
                        target = file
                        body.byteStream().use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (file.length() == 0L) error("Downloaded image is empty")
                        trace(
                            traceId,
                            "download_complete",
                            "index=$index attempt=${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS bytes=${file.length()} elapsed_ms=${elapsedSince(downloadStartedAt)}",
                        )
                        return@withContext ImageGenerationItem(
                            mimeType = mimeType,
                            localPath = file.absolutePath,
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    target?.delete()
                    throw e
                } catch (e: Exception) {
                    // 半截文件不能留给下一次重试, 否则失败一次就漏一个临时文件.
                    target?.delete()
                    lastError = e
                    trace(
                        traceId,
                        "download_failed",
                        "index=$index attempt=${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS error=${e.javaClass.simpleName}",
                    )
                    Log.w(TAG, "Image download failed (attempt ${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS)", e)
                    if (attempt < IMAGE_DOWNLOAD_ATTEMPTS - 1) {
                        kotlinx.coroutines.delay(IMAGE_DOWNLOAD_RETRY_DELAY_MS shl attempt)
                    }
                }
            }
            throw lastError ?: java.io.IOException("Failed to download generated image")
        }

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

        /**
         * 图片下载的硬上限, 不能继承聊天 client 那个为 SSE 设的 10 分钟 readTimeout.
         * 3 次重试各 60s 最坏要 180s 才报错, 比"图早就出好了"的体感差太远, 收到 25s:
         * 图床正常时一张图远用不到这个数, 超了基本就是断流, 早失败早重试.
         */
        private const val IMAGE_DOWNLOAD_TIMEOUT_SECONDS = 25L
        private const val IMAGE_DOWNLOAD_ATTEMPTS = 3
        private const val IMAGE_DOWNLOAD_RETRY_DELAY_MS = 500L
        private const val IMAGE_HTTP2_PING_INTERVAL_SECONDS = 20L
        private const val CHAT_HTTP2_PING_INTERVAL_SECONDS = 15L
        private const val IMAGE_TASK_POLL_INTERVAL_MS = 3_000L
        private const val IMAGE_TASK_POLL_TIMEOUT_MS = 31 * 60 * 1000L

        private fun traceId(value: String): String = value.ifBlank { "provider-untracked" }

        private fun elapsedSince(startedAt: Long): Long = SystemClock.elapsedRealtime() - startedAt

        private fun trace(traceId: String, stage: String, details: String) {
            Log.i(TRACE_TAG, "trace=$traceId stage=$stage $details")
        }
    }
}

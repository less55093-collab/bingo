package me.rerere.rikkahub.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.localizedChatMessage
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.resume

/**
 * Owns image generation so it survives the page that started it.
 *
 * Mirrors the [ChatService] arrangement: the job runs on [AppScope], state lives here, and screens
 * only observe. `ImgGenVM` used `viewModelScope`, which cancelled generation the moment the user
 * navigated away — the same reason chat generation was moved off the ViewModel.
 */
class ImageGenerationManager(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
    private val appEventBus: AppEventBus,
) {
    /**
     * [prompt] is kept in state so the UI can clear the input box on submit and still show what is
     * being generated — the user read an un-cleared box as "nothing was sent".
     */
    data class State(
        val generating: Boolean = false,
        val prompt: String = "",
        val images: List<GeneratedFile> = emptyList(),
        val error: String? = null,
        /**
         * 生成开始的时刻(elapsedRealtime), 未在生成时为 0. 进度按它推算而不是存一个百分比,
         * 这样用户退出页面再回来, 进度条能接着走而不是从头开始.
         */
        val startedAt: Long = 0L,
        /**
         * 这次请求是图片编辑而不是生图。编辑在上游明显更慢(实测 30-90s, 生图约 35-40s),
         * 进度曲线要用不同的标尺, 否则编辑时进度会早早贴住上限看起来像卡死。
         */
        val editing: Boolean = false,
    )

    data class GeneratedFile(val path: String, val partial: Boolean)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private val foregroundLeaseMutex = Mutex()
    private var foregroundTaskCount = 0

    /**
     * Guards retry: once an image is on disk and in the gallery, retrying would duplicate it. Scoped
     * per call rather than per manager because the in-chat tool and the page can generate at once.
     */
    private class Committed {
        var value = false
    }

    fun generate(prompt: String, size: String, numOfImages: Int) {
        start(prompt, origin = ORIGIN_STANDALONE_GENERATE) { model, provider, traceId ->
            providerManager.getProviderByType(provider).generateImage(
                provider,
                ImageGenerationParams(
                    model = model,
                    prompt = prompt,
                    numOfImages = numOfImages,
                    size = size,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    traceId = traceId,
                ),
            )
        }
    }

    fun edit(prompt: String, size: String, numOfImages: Int, referenceImages: List<String>) {
        start(
            prompt,
            sourcePaths = referenceImages.joinToString("\n"),
            editing = true,
            origin = ORIGIN_STANDALONE_EDIT,
        ) { model, provider, traceId ->
            providerManager.getProviderByType(provider).editImage(
                provider,
                ImageEditParams(
                    model = model,
                    prompt = prompt,
                    images = referenceImages,
                    numOfImages = numOfImages,
                    size = size,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                    traceId = traceId,
                ),
            )
        }
    }

    /**
     * Starts a chat-tool request from the same application-scoped owner as the standalone page.
     *
     * Tool execution still waits for the local file path it must return, but the actual network,
     * download, retry and persistence work is no longer a child of the chat UI coroutine. Navigating
     * away from a chat therefore cannot interrupt a successfully started image request.
     */
    suspend fun generateForTool(prompt: String, size: String): List<File> {
        val traceId = nextTraceId()
        val startedAt = SystemClock.elapsedRealtime()
        val files = generateAndPersist(
            prompt = prompt,
            sourcePaths = null,
            origin = ORIGIN_TOOL_GENERATE,
            traceId = traceId,
            request = { model, provider, requestTraceId ->
                providerManager.getProviderByType(provider).generateImage(
                    provider,
                    ImageGenerationParams(
                        model = model,
                        prompt = prompt,
                        numOfImages = 1,
                        size = size,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                        traceId = requestTraceId,
                    ),
                )
            },
        )
        trace(traceId, "tool_complete", "files=${files.size} elapsed_ms=${elapsedSince(startedAt)}")
        return files
    }

    /**
     * Protects the complete chat turn that may call the image tool.
     *
     * The first model request has to stay alive long enough to emit `generate_image`; protecting only
     * [generateForTool] leaves a gap where Android can suspend or abort that request in the background.
     * The nested tool request takes a second lease, so the service cannot stop between the model
     * response and the billable image POST.
     */
    suspend fun <T> withChatGenerationProtection(
        conversationId: String,
        block: suspend () -> T,
    ): T {
        val traceId = nextTraceId()
        val startedAt = SystemClock.elapsedRealtime()
        trace(traceId, "chat_protection_start", "conversation=$conversationId")
        val foregroundLease = acquireForegroundLease(traceId)
        check(foregroundLease) { "Unable to protect image generation in the background" }
        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                releaseForegroundLease(foregroundLease)
            }
            trace(traceId, "chat_protection_stop", "elapsed_ms=${elapsedSince(startedAt)}")
        }
    }

    private fun start(
        prompt: String,
        sourcePaths: String? = null,
        editing: Boolean = false,
        origin: String,
        request: suspend (
            me.rerere.ai.provider.Model,
            me.rerere.ai.provider.ProviderSetting,
            String,
        ) -> Flow<ImageGenerationItem>,
    ) {
        if (prompt.isBlank() || _state.value.generating) return
        val previous = job
        previous?.cancel()
        _state.value = State(
            generating = true,
            prompt = prompt,
            startedAt = SystemClock.elapsedRealtime(),
            editing = editing,
        )
        job = appScope.launch {
            // 等旧请求真正断开再发新的: 否则两个请求同时在飞, 上游会各计一次费.
            previous?.join()
            try {
                generateAndPersist(
                    prompt = prompt,
                    sourcePaths = sourcePaths,
                    origin = origin,
                    request = request,
                    onImagesChanged = { images ->
                        _state.value = _state.value.copy(images = images)
                    },
                )
                _state.value = _state.value.copy(generating = false, startedAt = 0L)
                appEventBus.tryEmit(
                    AppEvent.ImageGenerationEnded(
                        prompt = prompt,
                        imageCount = _state.value.images.count { !it.partial },
                        error = null,
                    )
                )
            } catch (e: CancellationException) {
                // 用户主动取消，不通知。
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate image", e)
                val message = readableError(e)
                _state.value = _state.value.copy(generating = false, error = message, startedAt = 0L)
                appEventBus.tryEmit(
                    AppEvent.ImageGenerationEnded(prompt = prompt, imageCount = 0, error = message)
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(generating = false, startedAt = 0L)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun reset() {
        job?.cancel()
        _state.value = State()
    }

    /**
     * Image generation sits on its own gateway group with its own key, carried by the image model's
     * `providerOverwrite`. Resolving through the chat model instead would send a chat key to the
     * image endpoint and fail.
     */
    private suspend fun resolveImageModel():
        Pair<me.rerere.ai.provider.Model, me.rerere.ai.provider.ProviderSetting> {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.imageGenerationModelId)
            ?: error("No image model available")
        val provider = model.findProvider(settings.providers)
            ?: error("No provider for image model")
        return model to provider
    }

    /** The common standalone/tool pipeline: resolve once, retry safely, materialize and persist. */
    private suspend fun generateAndPersist(
        prompt: String,
        sourcePaths: String?,
        origin: String,
        traceId: String = nextTraceId(),
        request: suspend (
            me.rerere.ai.provider.Model,
            me.rerere.ai.provider.ProviderSetting,
            String,
        ) -> Flow<ImageGenerationItem>,
        onImagesChanged: (List<GeneratedFile>) -> Unit = {},
    ): List<File> {
        val startedAt = SystemClock.elapsedRealtime()
        trace(traceId, "manager_start", "origin=$origin prompt_chars=${prompt.length}")
        val foregroundLease = acquireForegroundLease(traceId)
        check(foregroundLease) { "Unable to protect image generation in the background" }
        try {
            val resolveStartedAt = SystemClock.elapsedRealtime()
            val (model, provider) = resolveImageModel()
            trace(
                traceId,
                "model_resolved",
                "origin=$origin elapsed_ms=${elapsedSince(resolveStartedAt)} model=${model.modelId}",
            )
            val files = withRetry(traceId) { committed, attempt ->
                // Retried attempts restart from scratch, so drop anything a failed attempt showed.
                onImagesChanged(emptyList())
                collectInto(
                    images = request(model, provider, traceId),
                    prompt = prompt,
                    modelName = model.displayName,
                    sourcePaths = sourcePaths,
                    committed = committed,
                    traceId = traceId,
                    attempt = attempt,
                    onImagesChanged = onImagesChanged,
                )
            }
            trace(
                traceId,
                "manager_complete",
                "origin=$origin files=${files.size} elapsed_ms=${elapsedSince(startedAt)}",
            )
            return files
        } finally {
            withContext(NonCancellable) {
                releaseForegroundLease(foregroundLease)
            }
        }
    }

    private suspend fun acquireForegroundLease(traceId: String): Boolean {
        foregroundLeaseMutex.lock()
        try {
            if (foregroundTaskCount > 0) {
                foregroundTaskCount++
                return true
            }
            if (ImageGenerationForegroundService.startAndAwait(context)) {
                foregroundTaskCount = 1
                trace(traceId, "foreground_protection_active", "waited_for_foreground=false")
                return true
            }
        } finally {
            foregroundLeaseMutex.unlock()
        }

        // Android can reject a foreground-service start when the model emits the tool call only
        // after the app is already backgrounded. Do not send an unprotected billable POST.
        trace(traceId, "foreground_protection_wait", "reason=start_rejected")
        awaitAppForeground()

        foregroundLeaseMutex.lock()
        return try {
            if (foregroundTaskCount > 0) {
                foregroundTaskCount++
                true
            } else if (ImageGenerationForegroundService.startAndAwait(context)) {
                foregroundTaskCount = 1
                trace(traceId, "foreground_protection_active", "waited_for_foreground=true")
                true
            } else {
                false
            }
        } finally {
            foregroundLeaseMutex.unlock()
        }
    }

    private suspend fun releaseForegroundLease(acquired: Boolean) {
        if (!acquired) return
        foregroundLeaseMutex.lock()
        try {
            foregroundTaskCount--
            if (foregroundTaskCount == 0) {
                ImageGenerationForegroundService.stop(context)
            }
        } finally {
            foregroundLeaseMutex.unlock()
        }
    }

    private suspend fun awaitAppForeground() = withContext(Dispatchers.Main.immediate) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext

        suspendCancellableCoroutine { continuation ->
            lateinit var observer: LifecycleEventObserver
            observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START && continuation.isActive) {
                    lifecycle.removeObserver(observer)
                    continuation.resume(Unit)
                }
            }
            lifecycle.addObserver(observer)
            continuation.invokeOnCancellation {
                lifecycle.removeObserver(observer)
            }
            // Covers a foreground transition between the initial check and observer registration.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && continuation.isActive) {
                lifecycle.removeObserver(observer)
                continuation.resume(Unit)
            }
        }
    }

    private suspend fun collectInto(
        images: Flow<ImageGenerationItem>,
        prompt: String,
        modelName: String,
        sourcePaths: String?,
        committed: Committed,
        traceId: String,
        attempt: Int,
        onImagesChanged: (List<GeneratedFile>) -> Unit,
    ): List<File> {
        val finals = mutableListOf<GeneratedFile>()
        val files = mutableListOf<File>()
        var previewFile: File? = null
        var index = 0
        images.collect { item ->
            previewFile?.delete()
            if (item.partial) {
                // Temp folder, not images/: previews are transient and must not accumulate in gallery storage.
                val file = File(
                    context.appTempFolder,
                    "imggen_preview_${System.currentTimeMillis()}_$index.png",
                ).let { item.materializeTo(it.absolutePath) }
                previewFile = file
                onImagesChanged(finals + GeneratedFile(file.absolutePath, partial = true))
            } else {
                previewFile = null
                trace(traceId, "image_received", "attempt=$attempt index=$index source=${item.sourceForLog()}")
                val file = persist(item, prompt, modelName, index++, sourcePaths, committed, traceId)
                finals.add(GeneratedFile(file.absolutePath, partial = false))
                files.add(file)
                onImagesChanged(finals.toList())
            }
        }
        return files
    }

    /**
     * 两种来源统一落地成文件: [ImageGenerationItem.localPath] 是已下载好的临时文件(搬过去即可),
     * 否则才是 base64 需要解码.
     */
    private suspend fun ImageGenerationItem.materializeTo(filePath: String): File =
        withContext(Dispatchers.IO) {
            val downloaded = localPath
            if (downloaded != null) {
                filesManager.moveImageFileTo(File(downloaded), filePath)
            } else {
                filesManager.createImageFileFromBase64(data, filePath)
            }
        }

    /** Same storage contract as the gallery expects: file under images/ plus a [GenMediaEntity] row. */
    private suspend fun persist(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        index: Int,
        sourcePaths: String?,
        committed: Committed,
        traceId: String,
    ): File {
        val timestamp = System.currentTimeMillis()
        val target = File(filesManager.getImagesDir(), "${timestamp}_${modelName}_$index.png")
        val materializeStartedAt = SystemClock.elapsedRealtime()
        val created = item.materializeTo(target.absolutePath)
        trace(
            traceId,
            "file_materialized",
            "index=$index source=${item.sourceForLog()} bytes=${created.length()} elapsed_ms=${elapsedSince(materializeStartedAt)}",
        )
        val databaseStartedAt = SystemClock.elapsedRealtime()
        genMediaRepository.insertMedia(
            GenMediaEntity(
                path = "images/${target.name}",
                modelId = modelName,
                prompt = prompt,
                createAt = timestamp,
                type = if (sourcePaths == null) {
                    GenMediaEntity.TYPE_IMAGE_GENERATION
                } else {
                    GenMediaEntity.TYPE_IMAGE_EDIT
                },
                sourcePaths = sourcePaths,
            )
        )
        trace(traceId, "database_inserted", "index=$index elapsed_ms=${elapsedSince(databaseStartedAt)}")
        committed.value = true
        return created
    }

    /**
     * The reported failure was `Software caused connection abort` mid-request — transient, and fatal
     * only because nothing retried it. Retries cover the request and the follow-up download of the
     * remote URL the gateway returns.
     */
    private suspend fun <T> withRetry(traceId: String, block: suspend (Committed, Int) -> T): T {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            val committed = Committed()
            try {
                trace(traceId, "attempt_start", "attempt=${attempt + 1}/$MAX_ATTEMPTS")
                return block(committed, attempt + 1)
            } catch (e: IOException) {
                lastError = e
                trace(
                    traceId,
                    "attempt_failed",
                    "attempt=${attempt + 1}/$MAX_ATTEMPTS error=${e.javaClass.simpleName}",
                )
                Log.w(TAG, "Image request failed (attempt ${attempt + 1}/$MAX_ATTEMPTS)", e)
                // Re-running after a partial success would insert duplicate gallery rows.
                if (committed.value) throw e
                // 上游按次计费: 请求一旦发出, 重试就是再花一次钱, 哪怕响应没读回来.
                // 所以只重试"根本没连上"的失败.
                if (!isSafeToRetry(e)) throw e
                if (attempt < MAX_ATTEMPTS - 1) delay(BASE_DELAY_MS shl attempt)
            }
        }
        throw lastError ?: IOException("Image request failed")
    }

    /**
     * True only when the request provably never reached the gateway, so a retry cannot be billed
     * twice. [SocketTimeoutException] is deliberately excluded: OkHttp throws it for both connect
     * and read timeouts, and a read timeout means the image was already generated and charged.
     */
    private fun isSafeToRetry(e: IOException): Boolean = when (e) {
        is SocketTimeoutException -> false
        is UnknownHostException, is ConnectException, is SSLHandshakeException -> true
        else -> false
    }

    // 绘画特有的两种配置缺失自己处理, 其余(余额/限流/鉴权/网络...)交给共用的中文映射.
    private fun readableError(e: Exception): String = when {
        e.message?.contains("No image model") == true ->
            context.getString(R.string.imggen_error_no_model)

        e.message?.contains("No provider") == true ->
            context.getString(R.string.imggen_error_no_provider)

        else -> e.localizedChatMessage(context)
    }

    companion object {
        private const val TAG = "ImageGenerationManager"
        private const val TRACE_TAG = "ImgGenTrace"
        private const val ORIGIN_STANDALONE_GENERATE = "standalone_generate"
        private const val ORIGIN_STANDALONE_EDIT = "standalone_edit"
        private const val ORIGIN_TOOL_GENERATE = "tool_generate"
        private const val MAX_ATTEMPTS = 3
        private const val BASE_DELAY_MS = 800L
        private val traceSequence = AtomicInteger(0)

        private fun nextTraceId(): String =
            "img-${SystemClock.elapsedRealtime().toString(36)}-${traceSequence.incrementAndGet().toString(36)}"

        private fun elapsedSince(startedAt: Long): Long = SystemClock.elapsedRealtime() - startedAt

        private fun trace(traceId: String, stage: String, details: String) {
            Log.i(TRACE_TAG, "trace=$traceId stage=$stage $details")
        }
    }

    private fun ImageGenerationItem.sourceForLog(): String = if (localPath != null) "url_download" else "base64"
}

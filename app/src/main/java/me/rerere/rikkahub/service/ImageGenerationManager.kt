package me.rerere.rikkahub.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageGenerationTerminalException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelRequestException
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.data.ai.tools.local.ImageGenerationVariant
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.auth.AuthTokenStore
import me.rerere.rikkahub.data.auth.PendingImageTask
import me.rerere.rikkahub.data.auth.recoveryApiKeyFingerprint
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.provider.providers.ImageTaskQueryException
import me.rerere.ai.ui.UIMessageAnnotation
import androidx.core.net.toUri
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.localizedChatMessage
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.uuid.Uuid
import javax.net.ssl.SSLHandshakeException

internal fun shouldFinalizeImageRecovery(error: Throwable, pendingStillExists: Boolean): Boolean =
    error.hasTerminalImageCause() || !pendingStillExists

private fun Throwable.hasTerminalImageCause(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is ImageGenerationTerminalException }

/** The durable request still exists; callers must not submit another billable generation. */
class ImageGenerationPendingException(val requestId: String, cause: Throwable) :
    IllegalStateException("图片任务已保留，连接恢复后将继续获取结果，请勿重复提交", cause)

class ImageGenerationBatchException(val files: List<File>, val failures: List<Throwable>) :
    IllegalStateException("部分图片尚未完成")

internal fun resolvePersistedImageProvider(
    model: Model,
    providers: List<ProviderSetting>,
    persistedProviderId: String?,
): ProviderSetting? {
    val overwrite = model.providerOverwrite
    if (persistedProviderId?.isNotBlank() == true &&
        overwrite?.id?.toString() == persistedProviderId
    ) {
        return overwrite.copyProvider(models = emptyList())
    }
    val modelProvider = model.findProvider(providers)
    return persistedProviderId?.takeIf(String::isNotBlank)?.let { id ->
        modelProvider?.takeIf { it.id.toString() == id }
            ?: providers.firstOrNull { it.id.toString() == id }
    } ?: modelProvider
}

/**
 * Holds an image lease across standalone-request hand-off, including an interrupted predecessor.
 *
 * The caller invokes this from `launch(UNDISPATCHED)`, so [GenerationProtectionManager.begin]
 * synchronously requests the foreground service before [previous] can suspend. `withActiveLease`
 * owns the same lease for the request itself; the outer close covers cancellation while waiting.
 */
internal suspend fun <T> withStandaloneImageGenerationLease(
    protectionManager: GenerationProtectionManager,
    previous: Job?,
    block: suspend () -> T,
): T {
    val lease = protectionManager.begin(GenerationKind.IMAGE)
    return try {
        previous?.join()
        protectionManager.withActiveLease(lease, block)
    } finally {
        withContext(NonCancellable) { lease.close() }
    }
}

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
    private val authTokenStore: AuthTokenStore,
    private val conversationRepository: ConversationRepository? = null,
    private val gatewayAPI: BingoGatewayAPI? = null,
    /** Inject the app-wide instance so chat and image tools share the same service lease. */
    private val generationProtectionManager: GenerationProtectionManager =
        GenerationProtectionManager(context),
) {
    /**
     * [prompt] is kept in state so the UI can clear the input box on submit and still show what is
     * being generated — the user read an un-cleared box as "nothing was sent".
     */
    data class State(
        val requestId: String? = null,
        val waitingForRecovery: Boolean = false,
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
    private val activeRequestIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val recoveringRequestIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val recoveryJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val standaloneRequestIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val hiddenRequestIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val recoveryMutex = Mutex()
    private val imagePermits = Semaphore(2)
    private val deliveryReceipts = ImageDeliveryReceiptStore(File(context.noBackupFilesDir, "image_delivery_receipts"))
    private val accountSyncMutex = Mutex()
    private var accountSyncCursor = "0"
    private var lastAccountSync = 0L
    private var syncAccountId: Long? = null
    private val accountJobs = java.util.concurrent.ConcurrentHashMap<String, Pair<Long?, Job>>()

    init {
        appScope.launch {
            authTokenStore.tokensFlow.collect {
                val accountId = authTokenStore.currentTaskAccountId()
                accountJobs.values.filter { it.first == null || it.first != accountId }
                    .forEach { (_, job) -> job.cancel(CancellationException("账号已退出，图片任务保留待恢复")) }
            }
        }
    }

    private fun showsTask(task: PendingImageTask): Boolean = task.origin != ORIGIN_TOOL_GENERATE &&
        task.requestId !in hiddenRequestIds &&
        (_state.value.requestId == task.requestId || !_state.value.generating)

    private suspend fun requireTaskAccount(task: PendingImageTask) {
        if (!authTokenStore.isCurrentAccount(task)) throw CancellationException("图片任务等待原账号登录后恢复")
    }

    private suspend fun finishDelivery(task: PendingImageTask, files: List<File>, error: String? = null) {
        requireTaskAccount(task)
        authTokenStore.updatePendingImageTaskResult(task.requestId, files.map { it.absolutePath }, error)
        if (task.conversationId != null && task.messageId != null && task.toolCallId != null) {
            requireTaskAccount(task)
            checkNotNull(conversationRepository).deliverImageResult(
                task.conversationId, task.messageId,
                UIMessageAnnotation.ImageDelivery(
                    toolCallId = task.toolCallId, requestId = task.requestId,
                    imageUrls = files.map { it.toUri().toString() }, error = error,
                ),
            )
        }
        requireTaskAccount(task)
        if (error == null) withContext(Dispatchers.IO) { deliveryReceipts.record(checkNotNull(task.accountId), task.requestId) }
        authTokenStore.removePendingImageTaskByRequestId(task.requestId)
    }

    /**
     * Guards retry: once an image is on disk and in the gallery, retrying would duplicate it. Scoped
     * per call rather than per manager because the in-chat tool and the page can generate at once.
     */
    private class Committed {
        var value = false
    }

    fun generate(prompt: String, size: String, numOfImages: Int) {
        start(
            prompt,
            size = size,
            numOfImages = numOfImages,
            origin = ORIGIN_STANDALONE_GENERATE,
        ) { model, provider, requestId, requiredFingerprint, traceId, selected, submitted, fallback, failed ->
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
                    idempotencyKey = requestId,
                    requiredApiKeyFingerprint = requiredFingerprint,
                    onImageKeySelected = selected,
                    onTaskSubmitted = submitted,
                    onAsyncFallback = fallback,
                    allowSynchronousFallback = false,
                    onTaskFailed = failed,
                ),
            )
        }
    }

    fun edit(prompt: String, size: String, numOfImages: Int, referenceImages: List<String>) {
        start(
            prompt,
            size = size,
            numOfImages = numOfImages,
            sourcePaths = referenceImages.joinToString("\n"),
            editing = true,
            origin = ORIGIN_STANDALONE_EDIT,
        ) { model, provider, requestId, requiredFingerprint, traceId, selected, submitted, fallback, failed ->
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
                    idempotencyKey = requestId,
                    requiredApiKeyFingerprint = requiredFingerprint,
                    onImageKeySelected = selected,
                    onTaskSubmitted = submitted,
                    onAsyncFallback = fallback,
                    allowSynchronousFallback = false,
                    onTaskFailed = failed,
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
            size = size,
            numOfImages = 1,
            sourcePaths = null,
            origin = ORIGIN_TOOL_GENERATE,
            traceId = traceId,
            request = { model, provider, requestId, requiredFingerprint, requestTraceId, selected, submitted, fallback, failed ->
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
                        idempotencyKey = requestId,
                        requiredApiKeyFingerprint = requiredFingerprint,
                        onImageKeySelected = selected,
                        onTaskSubmitted = submitted,
                        onAsyncFallback = fallback,
                        allowSynchronousFallback = false,
                        onTaskFailed = failed,
                    ),
                )
            },
        )
        trace(traceId, "tool_complete", "files=${files.size} elapsed_ms=${elapsedSince(startedAt)}")
        return files
    }

    suspend fun editForTool(
        prompt: String,
        size: String,
        referenceImages: List<String>,
    ): List<File> {
        require(referenceImages.isNotEmpty()) { "At least one reference image is required" }
        val traceId = nextTraceId()
        val startedAt = SystemClock.elapsedRealtime()
        val files = generateAndPersist(
            prompt = prompt,
            size = size,
            numOfImages = 1,
            sourcePaths = referenceImages.joinToString("\n"),
            origin = ORIGIN_TOOL_GENERATE,
            traceId = traceId,
            request = { model, provider, requestId, requiredFingerprint, requestTraceId, selected, submitted, fallback, failed ->
                providerManager.getProviderByType(provider).editImage(
                    provider,
                    ImageEditParams(
                        model = model,
                        prompt = prompt,
                        images = referenceImages,
                        numOfImages = 1,
                        size = size,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                        traceId = requestTraceId,
                        idempotencyKey = requestId,
                        requiredApiKeyFingerprint = requiredFingerprint,
                        onImageKeySelected = selected,
                        onTaskSubmitted = submitted,
                        onAsyncFallback = fallback,
                        allowSynchronousFallback = false,
                        onTaskFailed = failed,
                    ),
                )
            },
        )
        trace(traceId, "tool_edit_complete", "files=${files.size} elapsed_ms=${elapsedSince(startedAt)}")
        return files
    }

    suspend fun editForToolBatch(
        variants: List<ImageGenerationVariant>,
        referenceImages: List<String>,
    ): List<File> = supervisorScope {
        val operation = currentCoroutineContext()[ImageOperationContext]
        variants.mapIndexed { index, variant ->
            async {
                runImageBatchItem(operation, index) {
                    editForTool(
                        prompt = variant.prompt,
                        size = variant.size,
                        referenceImages = referenceImages,
                    )
                }
            }
        }.awaitAll().let { results ->
            val files = results.flatMap { it.getOrNull().orEmpty() }
            val failures = results.mapNotNull { it.exceptionOrNull() }
            if (failures.isNotEmpty()) throw ImageGenerationBatchException(files, failures)
            files
        }
    }

    suspend fun generateForToolBatch(variants: List<ImageGenerationVariant>): List<File> = supervisorScope {
        val operation = currentCoroutineContext()[ImageOperationContext]
        variants.mapIndexed { index, variant ->
            async {
                runImageBatchItem(operation, index) {
                    generateForTool(prompt = variant.prompt, size = variant.size)
                }
            }
        }.awaitAll().let { results ->
            val files = results.flatMap { it.getOrNull().orEmpty() }
            val failures = results.mapNotNull { it.exceptionOrNull() }
            if (failures.isNotEmpty()) throw ImageGenerationBatchException(files, failures)
            files
        }
    }

    private suspend fun runImageBatchItem(
        operation: ImageOperationContext?, index: Int, block: suspend () -> List<File>,
    ): Result<List<File>> = try {
        Result.success(if (operation == null) block() else withContext(operation.copy(itemIndex = index)) { block() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun start(
        prompt: String,
        size: String,
        numOfImages: Int,
        sourcePaths: String? = null,
        editing: Boolean = false,
        origin: String,
        request: suspend (
            me.rerere.ai.provider.Model,
            me.rerere.ai.provider.ProviderSetting,
            String,
            String?,
            String,
            suspend (String) -> Unit,
            suspend (String) -> Unit,
            suspend () -> Unit,
            suspend (String) -> Unit,
        ) -> Flow<ImageGenerationItem>,
    ) {
        if (prompt.isBlank() || _state.value.generating) return
        val requestId = "bingo_" + UUID.randomUUID().toString()
        _state.value = State(
            requestId = requestId,
            generating = true,
            prompt = prompt,
            startedAt = SystemClock.elapsedRealtime(),
            editing = editing,
        )
        job = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // begin() runs synchronously before previous.join() can suspend. Holding this
                // provisional lease prevents a background transition in that hand-off window.
                val files = withStandaloneImageGenerationLease(generationProtectionManager, null) {
                    generateAndPersistWhileProtected(
                        prompt = prompt,
                        size = size,
                        numOfImages = numOfImages,
                        sourcePaths = sourcePaths,
                        origin = origin,
                        requestId = requestId,
                        request = request,
                        onImagesChanged = { images ->
                            if (_state.value.requestId == requestId && requestId !in hiddenRequestIds) {
                                _state.value = _state.value.copy(images = images)
                            }
                        },
                    )
                }
                if (_state.value.requestId == requestId) {
                    _state.value = _state.value.copy(generating = false, waitingForRecovery = false, startedAt = 0L)
                }
                appEventBus.tryEmit(
                    AppEvent.ImageGenerationEnded(
                        prompt = prompt,
                        imageCount = files.size,
                        error = null,
                    )
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) { deferPendingTask(e, requestId) }
                throw e
            } catch (e: Exception) {
                if (deferPendingTask(e, requestId)) return@launch
                Log.e(TAG, "Failed to generate image: ${e.javaClass.simpleName}")
                val message = readableError(e)
                if (_state.value.requestId == requestId && requestId !in hiddenRequestIds) {
                    _state.value = _state.value.copy(generating = false, waitingForRecovery = false, error = message, startedAt = 0L)
                }
                appEventBus.tryEmit(
                    AppEvent.ImageGenerationEnded(prompt = prompt, imageCount = 0, error = message)
                )
            }
        }
    }

    fun cancel() {
        cancelStandaloneWork()
        _state.value = _state.value.copy(generating = false, waitingForRecovery = false, startedAt = 0L)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun reset() {
        cancelStandaloneWork()
        _state.value = State()
    }

    /** Stops waiting in this screen. An accepted server operation remains recoverable. */
    private fun cancelStandaloneWork() {
        hiddenRequestIds.addAll(standaloneRequestIds)
        _state.value.requestId?.let { hiddenRequestIds.add(it) }
        ImageGenerationRecoveryScheduler.enqueue(context, delayedHeartbeat = true)
    }

    /**
     * Hands persisted tasks to [AppScope] after Android recreates this process.
     *
     * Recovery deliberately does not join the launched jobs. WorkManager is only the durable wake-up
     * source; the application-scoped jobs and [GenerationForegroundService] own the actual request.
     * Each job is registered before it starts, so a user cancellation can always reach recovery
     * work. WorkManager remains the durable fallback if Android kills the process before the
     * application-scoped job acquires foreground protection.
     */
    suspend fun recoverPendingTasks(): Unit = recoveryMutex.withLock {
        reconcileAccountTasks()
        val tasks = authTokenStore.currentPendingImageTasks()
        if (tasks.isEmpty()) return

        tasks.forEach { task ->
            val requestId = task.requestId.ifBlank { task.taskId ?: return@forEach }
            if (activeRequestIds.contains(requestId) ||
                !recoveringRequestIds.add(requestId)
            ) {
                return@forEach
            } else {
                if (task.origin != ORIGIN_TOOL_GENERATE) standaloneRequestIds.add(requestId)
                val recoveryJob = appScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        recoverPendingTask(task.copy(requestId = requestId))
                    } finally {
                        recoveryJobs.remove(requestId)
                        if (task.origin != ORIGIN_TOOL_GENERATE &&
                            requestId !in hiddenRequestIds &&
                            !(_state.value.requestId == requestId && _state.value.waitingForRecovery)
                        ) {
                            standaloneRequestIds.remove(requestId)
                            hiddenRequestIds.remove(requestId)
                        }
                    }
                }
                recoveryJobs[requestId] = recoveryJob
                recoveryJob.start()
            }
        }
    }

    suspend fun hasPendingTasks(): Boolean = accountSyncCursor != "0" || authTokenStore.currentPendingImageTasks().isNotEmpty()

    suspend fun reconcileAccountImageTasks() { reconcileAccountTasks() }

    suspend fun hasSavedRecoveryCredentials(): Boolean = authTokenStore.currentPendingImageTasks()
        .all { (it.taskId != null && gatewayAPI != null) || (it.providerSnapshot != null && it.modelSnapshot != null) }

    /** Account lookup repairs a lost POST acknowledgement and missing local task records. */
    private suspend fun reconcileAccountTasks() = accountSyncMutex.withLock {
        val api = gatewayAPI ?: return@withLock
        val accountId = authTokenStore.currentTaskAccountId() ?: return@withLock
        if (syncAccountId != accountId) {
            syncAccountId = accountId
            accountSyncCursor = "0"
            lastAccountSync = 0L
        }
        if (SystemClock.elapsedRealtime() - lastAccountSync < 30_000 && lastAccountSync != 0L) return@withLock
        try {
            val page = api.imageTasks(accountSyncCursor)
            if (authTokenStore.currentTaskAccountId() != accountId) return@withLock
            val pending = authTokenStore.currentPendingImageTasks().associateBy { it.requestId }
            page.data.forEach { remote ->
                val local = pending[remote.requestId]
                if (local != null && local.taskId == null) {
                    authTokenStore.setPendingImageTaskId(local.requestId, remote.id)
                } else if (local == null && remote.requestId.startsWith("bingo_") &&
                    remote.status != "failed" &&
                    !withContext(Dispatchers.IO) { deliveryReceipts.contains(accountId, remote.requestId) }) {
                    val origin = conversationRepository?.findImageTaskOrigin(remote.requestId)
                    if (authTokenStore.currentTaskAccountId() != accountId) return@withLock
                    authTokenStore.savePendingImageTask(PendingImageTask(
                        accountId = accountId, requestId = remote.requestId, taskId = remote.id,
                        conversationId = origin?.conversationId, messageId = origin?.messageId,
                        toolCallId = origin?.toolCallId, operationId = origin?.operationId, itemId = origin?.itemId,
                        prompt = "从账号恢复的图片", modelName = "生图",
                        origin = if (origin != null) ORIGIN_TOOL_GENERATE else "account_recovery",
                    ))
                }
            }
            accountSyncCursor = page.nextCursor.ifBlank { "0" }
            lastAccountSync = SystemClock.elapsedRealtime()
            if (accountSyncCursor != "0") ImageGenerationRecoveryScheduler.enqueue(context, delayedHeartbeat = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Older gateways have no account endpoint. Existing durable records still recover
            // through their original key; a catalog failure never triggers image creation.
            lastAccountSync = SystemClock.elapsedRealtime()
            Log.w(TAG, "Account task reconciliation deferred: ${error.javaClass.simpleName}")
        }
    }

    private fun accountTaskImages(task: PendingImageTask, traceId: String): Flow<ImageGenerationItem> = flow {
        val api = checkNotNull(gatewayAPI)
        val parser = providerManager.getProviderByType(ProviderSetting.OpenAI()) as OpenAIProvider
        while (true) {
            requireTaskAccount(task)
            val remote = try {
                api.imageTask(checkNotNull(task.taskId))
            } catch (error: retrofit2.HttpException) {
                if (error.code() == 404 && task.providerSnapshot is ProviderSetting.OpenAI) {
                    emitAll(providerManager.getProviderByType(task.providerSnapshot).resumeImageTask(
                        providerSetting = task.providerSnapshot, taskId = task.taskId!!,
                        traceId = traceId, apiKeyFingerprint = task.apiKeyFingerprint,
                    ))
                    return@flow
                }
                if (error.code() == 410 && error.response()?.errorBody()?.string()
                        ?.contains("IMAGE_TASK_RESULT_EXPIRED") == true) {
                    throw ImageGenerationTerminalException("图片结果已超过服务端保留期限", error)
                }
                throw IOException("图片任务暂时无法查询，稍后恢复", error)
            }
            requireTaskAccount(task)
            if (remote.id != task.taskId) throw IOException("图片任务查询返回了不匹配的任务，等待重新获取")
            when (remote.status) {
                "completed", "succeeded", "success" -> {
                    val result = remote.result ?: throw IOException("图片已生成，等待服务端提供结果")
                    val images = try { parser.parseImageResponse(result.toString(), traceId, remote.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        throw ImageTaskQueryException(remote.id, message = "图片已生成，等待下载恢复：${error.javaClass.simpleName}")
                    }
                    images.forEach { emit(it) }
                    return@flow
                }
                "failed", "cancelled", "expired" -> throw ImageGenerationTerminalException(
                    remote.error?.toString() ?: "服务端图片任务失败")
                else -> delay(3_000)
            }
        }
    }

    private suspend fun deferPendingTask(error: Throwable, requestId: String?): Boolean {
        if (requestId.isNullOrBlank() || error.hasTerminalImageCause()) return false
        val task = authTokenStore.currentPendingImageTasks().firstOrNull { it.requestId == requestId } ?: return false
        if (showsTask(task)) {
            standaloneRequestIds.add(requestId)
            _state.value = _state.value.copy(
                requestId = requestId, prompt = task.prompt, generating = true,
                waitingForRecovery = true, error = null, startedAt = 0L,
            )
        }
        ImageGenerationRecoveryScheduler.enqueue(context, delayedHeartbeat = true)
        return true
    }

    private suspend fun recoverPendingTask(task: PendingImageTask) {
        val traceId = nextTraceId()
        val requestId = task.requestId.ifBlank { task.taskId ?: return }
        if (!activeRequestIds.add(requestId)) {
            recoveringRequestIds.remove(requestId)
            return
        }
        try {
            requireTaskAccount(task)
            currentCoroutineContext()[Job]?.let { accountJobs[requestId] = task.accountId to it }
            if (task.deliveryError != null || (task.completedPaths.isNotEmpty() &&
                    task.completedPaths.all { filesManager.isValidImageFile(File(it)) })) {
                val files = task.completedPaths.map(::File)
                finishDelivery(task, files, task.deliveryError)
                if (showsTask(task)) {
                    _state.value = State(requestId = task.requestId, prompt = task.prompt,
                        images = files.map { GeneratedFile(it.absolutePath, false) }, error = task.deliveryError)
                }
                appEventBus.tryEmit(AppEvent.ImageGenerationEnded(task.prompt, files.size, task.deliveryError))
                return
            }
            if (showsTask(task)) {
                _state.value = State(
                    requestId = requestId,
                    generating = true,
                    prompt = task.prompt,
                    startedAt = SystemClock.elapsedRealtime(),
                    editing = task.editing || task.sourcePaths != null,
                )
            }
            imagePermits.withPermit { generationProtectionManager.withProtection(GenerationKind.IMAGE) {
                requireTaskAccount(task)
                if (task.taskId != null && gatewayAPI != null) {
                    val files = collectInto(accountTaskImages(task, traceId), task.prompt, task.modelName,
                        task.sourcePaths, Committed(), traceId, 1, requestId, accountTask = task,
                        onImagesChanged = { images -> if (showsTask(task)) _state.value = _state.value.copy(images = images) })
                    finishDelivery(task, files)
                    if (showsTask(task)) _state.value = _state.value.copy(generating = false, waitingForRecovery = false, startedAt = 0L)
                    appEventBus.tryEmit(AppEvent.ImageGenerationEnded(task.prompt, files.size, null))
                    return@withProtection
                }
                if (task.taskId == null) validateRecoveryInputs(task)
                val (model, providerSetting) = resolveImageModel(task)
                if (providerSetting !is ProviderSetting.OpenAI) {
                    throw ImageGenerationTerminalException(
                        "No provider capable of recovering the image task"
                    )
                }
                val provider = providerManager.getProviderByType(providerSetting)
                val committed = Committed()
                val recoveryFingerprint = task.recoveryApiKeyFingerprint()
                var submittedTaskId = task.taskId
                val onSubmitted: suspend (String) -> Unit = { taskId ->
                    requireTaskAccount(task)
                    submittedTaskId = taskId
                    authTokenStore.setPendingImageTaskId(requestId, taskId)
                    ImageGenerationRecoveryScheduler.enqueue(context)
                }
                val onSelected: suspend (String) -> Unit = { fingerprint ->
                    requireTaskAccount(task)
                    authTokenStore.setPendingImageTaskKeyFingerprint(requestId, fingerprint)
                }
                val onFallback: suspend () -> Unit = {
                    // A synchronous fallback has no server task id and the sync endpoint is not
                    // guaranteed to honor Idempotency-Key. Remove the replay record before sending
                    // it; replaying after a process death could charge the same image twice.
                    authTokenStore.removePendingImageTaskByRequestId(requestId)
                }
                val onFailed: suspend (String) -> Unit = { requireTaskAccount(task) }
                val images = if (submittedTaskId != null) {
                    provider.resumeImageTask(
                        providerSetting = providerSetting,
                        taskId = submittedTaskId!!,
                        customHeaders = model.customHeaders,
                        traceId = traceId,
                        apiKeyFingerprint = recoveryFingerprint.orEmpty(),
                        onTaskFailed = onFailed,
                    )
                } else {
                    if (!providerSetting.useAsyncImageTasks) {
                        throw ImageGenerationTerminalException(
                            "Cannot safely replay a non-asynchronous image request"
                        )
                    }
                    if (task.editing || task.sourcePaths != null) {
                        provider.editImage(
                            providerSetting,
                            ImageEditParams(
                                model = model,
                                prompt = task.prompt,
                                images = task.sourcePaths.orEmpty().split("\n").filter(String::isNotBlank),
                                numOfImages = task.numOfImages.coerceAtLeast(1),
                                size = task.size,
                                customHeaders = model.customHeaders,
                                customBody = model.customBodies,
                                traceId = traceId,
                                idempotencyKey = requestId,
                                requiredApiKeyFingerprint = recoveryFingerprint,
                                onImageKeySelected = onSelected,
                                onTaskSubmitted = onSubmitted,
                                onAsyncFallback = onFallback,
                                allowSynchronousFallback = false,
                                onTaskFailed = onFailed,
                            ),
                        )
                    } else {
                        provider.generateImage(
                            providerSetting,
                            ImageGenerationParams(
                                model = model,
                                prompt = task.prompt,
                                numOfImages = task.numOfImages.coerceAtLeast(1),
                                size = task.size,
                                customHeaders = model.customHeaders,
                                customBody = model.customBodies,
                                traceId = traceId,
                                idempotencyKey = requestId,
                                requiredApiKeyFingerprint = recoveryFingerprint,
                                onImageKeySelected = onSelected,
                                onTaskSubmitted = onSubmitted,
                                onAsyncFallback = onFallback,
                                allowSynchronousFallback = false,
                                onTaskFailed = onFailed,
                            ),
                        )
                    }
                }
                val files = collectInto(
                    images = images,
                    prompt = task.prompt,
                    modelName = task.modelName,
                    sourcePaths = task.sourcePaths,
                    committed = committed,
                    traceId = traceId,
                    attempt = 1,
                    requestId = requestId,
                    accountTask = task,
                    onImagesChanged = { images ->
                        if (showsTask(task)) {
                            _state.value = State(
                                requestId = requestId,
                                generating = true,
                                prompt = task.prompt,
                                images = images,
                                startedAt = SystemClock.elapsedRealtime(),
                                editing = task.sourcePaths != null,
                            )
                        }
                    },
                )
                finishDelivery(task.copy(taskId = submittedTaskId), files)
                if (showsTask(task)) {
                    _state.value = _state.value.copy(generating = false, waitingForRecovery = false, startedAt = 0L)
                }
                appEventBus.tryEmit(
                    AppEvent.ImageGenerationEnded(
                        prompt = task.prompt,
                        imageCount = files.size,
                        error = null,
                    )
                )
                trace(traceId, "task_recovered", "task_id=${submittedTaskId ?: "pending"} files=${files.size}")
            } }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { deferPendingTask(e, requestId) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to recover image task ${task.taskId}; error=${e.javaClass.simpleName}")
            val pendingStillExists = runCatching {
                authTokenStore.currentPendingImageTasks().any { pending ->
                    pending.requestId.ifBlank { pending.taskId.orEmpty() } == requestId
                }
            }.getOrDefault(true)
            if (shouldFinalizeImageRecovery(e, pendingStillExists)) {
                val message = readableError(ModelRequestException(task.modelSnapshot?.modelId ?: task.modelName, e))
                val removed = runCatching {
                    withContext(NonCancellable) {
                        finishDelivery(task, emptyList(), message)
                    }
                }.onFailure { cleanupError ->
                    Log.w(TAG, "Unable to finalize failed image task ${task.taskId}", cleanupError)
                }.isSuccess
                if (removed) {
                    if (showsTask(task)) {
                        _state.value = _state.value.copy(
                            generating = false,
                            waitingForRecovery = false,
                            error = message,
                            startedAt = 0L,
                        )
                    }
                    appEventBus.emit(
                        AppEvent.ImageGenerationEnded(
                            prompt = task.prompt,
                            imageCount = 0,
                            error = message,
                        )
                    )
                    trace(traceId, "task_recovery_failed", "task_id=${task.taskId ?: "pending"}")
                }
            } else {
                deferPendingTask(e, requestId)
            }
        } finally {
            accountJobs.remove(requestId)
            activeRequestIds.remove(requestId)
            recoveringRequestIds.remove(requestId)
        }
    }

    /**
     * Image generation sits on its own gateway group with its own key, carried by the image model's
     * `providerOverwrite`. Resolving through the chat model instead would send a chat key to the
     * image endpoint and fail.
     */
    private suspend fun resolveImageModel(task: PendingImageTask? = null):
        Pair<me.rerere.ai.provider.Model, me.rerere.ai.provider.ProviderSetting> {
        if (task?.modelSnapshot != null && task.providerSnapshot != null) {
            return task.modelSnapshot to task.providerSnapshot
        }
        if (task != null && task.modelId.isNotBlank()) {
            val routing = authTokenStore.currentGatewayRouting()
            val candidates = listOfNotNull(routing.image) + routing.imageHistory.asReversed()
            for (binding in candidates) {
                if (task.apiKeyFingerprint.isNotBlank() &&
                    me.rerere.ai.util.keyFingerprint(binding.key) != task.apiKeyFingerprint) continue
                val cached = me.rerere.rikkahub.data.auth.ProviderInjector.models(
                    binding, me.rerere.rikkahub.data.model.gateway.GatewayPurpose.IMAGE,
                ).firstOrNull { it.id.toString() == task.modelId } ?: continue
                val provider = cached.providerOverwrite as? ProviderSetting.OpenAI ?: continue
                return cached to provider.copy(useAsyncImageTasks = true)
            }
        }
        val settings = settingsStore.settingsFlow.first()
        val persistedModelValue = task?.modelId?.takeIf(String::isNotBlank)
        val model = if (persistedModelValue != null) {
            val persistedModelId = runCatching { Uuid.parse(persistedModelValue) }.getOrNull()
                ?: throw ImageGenerationTerminalException("No image model available for recovery")
            settings.providers.findModelById(persistedModelId)
                ?: throw ImageGenerationTerminalException("No image model available for recovery")
        } else {
            settings.findModelById(settings.imageGenerationModelId)
                ?: if (task == null) {
                    error("No image model available")
                } else {
                    throw ImageGenerationTerminalException("No image model available for recovery")
                }
        }
        val persistedProviderId = task?.providerId?.takeIf(String::isNotBlank)
        val provider = resolvePersistedImageProvider(model, settings.providers, persistedProviderId)
            ?: if (task == null) {
                error("No provider for image model")
            } else {
                throw ImageGenerationTerminalException("No provider for image model recovery")
            }
        val recoveryProvider = if (task != null && provider is ProviderSetting.OpenAI) {
            provider.copy(
                baseUrl = task.providerBaseUrl.takeIf(String::isNotBlank) ?: provider.baseUrl,
                // A pending record is proof that the original request used the async contract.
                // Recovery still targets that contract if the user toggled the setting meanwhile.
                useAsyncImageTasks = true,
            )
        } else {
            provider
        }
        return model to recoveryProvider
    }

    private fun validateRecoveryInputs(task: PendingImageTask) {
        if (!task.editing && task.sourcePaths == null) return
        validateImageEditSources(task.sourcePaths)
    }

    private fun validateImageEditSources(sourcePaths: String?) {
        val sources = sourcePaths.orEmpty().split("\n").filter(String::isNotBlank)
        if (sources.isEmpty() || sources.any { path -> !File(path).isFile }) {
            throw ImageGenerationTerminalException(
                "Image edit source is no longer available"
            )
        }
    }

    /** The common standalone/tool pipeline: resolve once, retry safely, materialize and persist. */
    private suspend fun generateAndPersist(
        prompt: String,
        size: String,
        numOfImages: Int,
        sourcePaths: String?,
        origin: String,
        traceId: String = nextTraceId(),
        request: suspend (
            me.rerere.ai.provider.Model,
            me.rerere.ai.provider.ProviderSetting,
            String,
            String?,
            String,
            suspend (String) -> Unit,
            suspend (String) -> Unit,
            suspend () -> Unit,
            suspend (String) -> Unit,
        ) -> Flow<ImageGenerationItem>,
        onImagesChanged: (List<GeneratedFile>) -> Unit = {},
    ): List<File> {
        val operation = currentCoroutineContext()[ImageOperationContext]
        val requestId = operation?.requestId ?: "bingo_" + UUID.randomUUID().toString()
        try {
            return generationProtectionManager.withProtection(GenerationKind.IMAGE) {
                generateAndPersistWhileProtected(
                    prompt = prompt,
                    size = size,
                    numOfImages = numOfImages,
                    sourcePaths = sourcePaths,
                    origin = origin,
                    requestId = requestId,
                    traceId = traceId,
                    request = request,
                    onImagesChanged = onImagesChanged,
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { deferPendingTask(e, requestId) }
            throw e
        } catch (e: Exception) {
            if (deferPendingTask(e, requestId)) throw ImageGenerationPendingException(requestId, e)
            throw e
        }
    }

    /** Caller holds an active image-generation lease for the complete request and persistence work. */
    private suspend fun generateAndPersistWhileProtected(
        prompt: String,
        size: String,
        numOfImages: Int,
        sourcePaths: String?,
        origin: String,
        requestId: String = "bingo_" + UUID.randomUUID().toString(),
        traceId: String = nextTraceId(),
        request: suspend (
            me.rerere.ai.provider.Model,
            me.rerere.ai.provider.ProviderSetting,
            String,
            String?,
            String,
            suspend (String) -> Unit,
            suspend (String) -> Unit,
            suspend () -> Unit,
            suspend (String) -> Unit,
        ) -> Flow<ImageGenerationItem>,
        onImagesChanged: (List<GeneratedFile>) -> Unit = {},
    ): List<File> {
        val startedAt = SystemClock.elapsedRealtime()
        trace(traceId, "manager_start", "origin=$origin prompt_chars=${prompt.length}")
        val operation = currentCoroutineContext()[ImageOperationContext]
        // A completed business operation stays completed even if the user changed model/key,
        // or an old tool snapshot is executed again after its pending record was acknowledged.
        if (operation != null) {
            val delivered = conversationRepository?.findImageDelivery(operation)
            val existingFiles = delivered?.imageUrls?.mapNotNull { url ->
                val uri = url.toUri()
                uri.path?.takeIf { uri.scheme == "file" }?.let(::File)
            }.orEmpty()
            if (existingFiles.isNotEmpty() && existingFiles.size == delivered?.imageUrls?.size &&
                existingFiles.all { filesManager.isValidImageFile(it) }) {
                return existingFiles
            }
            val accountId = authTokenStore.currentTaskAccountId()
            if (delivered != null || (accountId != null && withContext(Dispatchers.IO) {
                    deliveryReceipts.contains(accountId, requestId)
                })) {
                throw ImageGenerationTerminalException("此生图请求已完成，请查看原聊天或作品；如需重新生图，请发送新消息。")
            }
        }
        if (sourcePaths != null) validateImageEditSources(sourcePaths)
        val resolveStartedAt = SystemClock.elapsedRealtime()
        val (model, provider) = resolveImageModel()
        trace(
            traceId,
            "model_resolved",
            "origin=$origin elapsed_ms=${elapsedSince(resolveStartedAt)} model=${model.modelId}",
        )
        val durableTask = provider is ProviderSetting.OpenAI && provider.useAsyncImageTasks
        if (!activeRequestIds.add(requestId)) {
            throw ImageGenerationPendingException(requestId, IllegalStateException("同一图片任务正在处理"))
        }
        try {
            if (origin != ORIGIN_TOOL_GENERATE) standaloneRequestIds.add(requestId)
            var persistedTask: PendingImageTask? = null
            if (durableTask) {
                val existing = authTokenStore.currentPendingImageTasks().firstOrNull { it.requestId == requestId }
                if (existing != null) {
                    activeRequestIds.remove(requestId)
                    ImageGenerationRecoveryScheduler.enqueue(context)
                    throw ImageGenerationPendingException(requestId, IllegalStateException("已有任务等待恢复"))
                }
                persistedTask = PendingImageTask(
                        accountId = authTokenStore.currentTaskAccountId(),
                        conversationId = operation?.conversationId,
                        messageId = operation?.messageId,
                        toolCallId = operation?.toolCallId,
                        operationId = operation?.operationId,
                        itemId = operation?.itemId,
                        modelSnapshot = model,
                        providerSnapshot = provider,
                        requestId = requestId,
                        prompt = prompt,
                        sourcePaths = sourcePaths,
                        modelName = model.displayName,
                        origin = origin,
                        modelId = model.id.toString(),
                        providerId = provider.id.toString(),
                        providerBaseUrl = provider.baseUrl,
                        size = size,
                        numOfImages = numOfImages.coerceAtLeast(1),
                        editing = sourcePaths != null,
                    )
                authTokenStore.savePendingImageTask(
                    persistedTask,
                )
                currentCoroutineContext()[Job]?.let { accountJobs[requestId] = persistedTask.accountId to it }
                if (origin != ORIGIN_TOOL_GENERATE && _state.value.requestId == requestId && requestId !in hiddenRequestIds) {
                    _state.value = _state.value.copy(requestId = requestId, waitingForRecovery = false)
                }
                ImageGenerationRecoveryScheduler.enqueue(context)
                trace(traceId, "request_persisted", "request_id=$requestId")
            }
            var submittedTaskId: String? = null
            val onTaskSubmitted: suspend (String) -> Unit = { taskId ->
                submittedTaskId = taskId
                if (durableTask) {
                    requireTaskAccount(persistedTask!!)
                    authTokenStore.setPendingImageTaskId(requestId, taskId)
                }
                trace(traceId, "task_persisted", "task_id=$taskId")
            }
            var selectedApiKeyFingerprint: String? = null
            val onImageKeySelected: suspend (String) -> Unit = { fingerprint ->
                // The first key is part of the billing/idempotency identity. Keep it stable for every
                // in-process retry, even when persisting the callback itself is delayed or fails.
                if (selectedApiKeyFingerprint == null) selectedApiKeyFingerprint = fingerprint
                if (durableTask) {
                    requireTaskAccount(persistedTask!!)
                    authTokenStore.setPendingImageTaskKeyFingerprint(requestId, fingerprint)
                }
                trace(traceId, "image_key_bound", "request_id=$requestId")
            }
            val onTaskFailed: suspend (String) -> Unit = { taskId ->
                persistedTask?.let { requireTaskAccount(it) }
                trace(traceId, "task_terminal", "task_id=$taskId reason=terminal_failure")
            }
            var asyncFallbackUsed = false
            val onAsyncFallback: suspend () -> Unit = {
                asyncFallbackUsed = true
                if (durableTask) {
                    // The synchronous endpoint has no durable task contract. Do not let a process
                    // restart replay an already-started fallback request with an unknown billing state.
                    authTokenStore.removePendingImageTaskByRequestId(requestId)
                }
                trace(traceId, "async_fallback", "request_id=$requestId reason=endpoint_unsupported")
            }
            try {
                val files = imagePermits.withPermit {
                  persistedTask?.let { requireTaskAccount(it) }
                  withRetry(traceId, idempotent = { durableTask && !asyncFallbackUsed }) { committed, attempt ->
                    // Retried attempts restart from scratch, so drop anything a failed attempt showed.
                    onImagesChanged(emptyList())
                    collectInto(
                        images = if (submittedTaskId != null && provider is ProviderSetting.OpenAI) {
                            // Once acknowledged, retry the same task's GET rather than its billable POST.
                            providerManager.getProviderByType(provider).resumeImageTask(
                                providerSetting = provider,
                                taskId = submittedTaskId!!,
                                customHeaders = model.customHeaders,
                                traceId = traceId,
                                apiKeyFingerprint = selectedApiKeyFingerprint.orEmpty(),
                                onTaskFailed = onTaskFailed,
                            )
                        } else request(
                            model,
                            provider,
                            requestId,
                            selectedApiKeyFingerprint,
                            traceId,
                            onImageKeySelected,
                            onTaskSubmitted,
                            onAsyncFallback,
                            onTaskFailed,
                        ),
                        prompt = prompt,
                        modelName = model.displayName,
                        sourcePaths = sourcePaths,
                        committed = committed,
                        traceId = traceId,
                        attempt = attempt,
                        requestId = requestId,
                        accountTask = persistedTask,
                        onImagesChanged = onImagesChanged,
                    )
                  }
                }
                if (durableTask) {
                    finishDelivery(persistedTask!!.copy(taskId = submittedTaskId,
                        apiKeyFingerprint = selectedApiKeyFingerprint.orEmpty()), files)
                    trace(traceId, "task_removed", "task_id=${submittedTaskId ?: "none"} reason=completed")
                }
                trace(
                    traceId,
                    "manager_complete",
                    "origin=$origin files=${files.size} elapsed_ms=${elapsedSince(startedAt)}",
                )
                return files
            } catch (terminal: ImageGenerationTerminalException) {
                if (durableTask) {
                    withContext(NonCancellable) {
                        finishDelivery(persistedTask!!.copy(taskId = submittedTaskId), emptyList(),
                            readableError(ModelRequestException(model.modelId, terminal)))
                    }
                }
                throw ModelRequestException(model.modelId, terminal)
            } catch (error: Exception) {
                if (error is CancellationException || error is ModelRequestException) throw error
                throw ModelRequestException(model.modelId, error)
            }
        } finally {
            accountJobs.remove(requestId)
            activeRequestIds.remove(requestId)
            if (origin != ORIGIN_TOOL_GENERATE) {
                standaloneRequestIds.remove(requestId)
                hiddenRequestIds.remove(requestId)
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
        requestId: String,
        accountTask: PendingImageTask? = null,
        onImagesChanged: (List<GeneratedFile>) -> Unit,
    ): List<File> {
        val finals = mutableListOf<GeneratedFile>()
        val files = mutableListOf<File>()
        var previewFile: File? = null
        var index = 0
        images.collect { item ->
            accountTask?.let { requireTaskAccount(it) }
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
                val file = persist(item, prompt, modelName, index++, sourcePaths, committed, traceId, requestId)
                finals.add(GeneratedFile(file.absolutePath, partial = false))
                files.add(file)
                onImagesChanged(finals.toList())
            }
        }
        check(files.isNotEmpty()) { "生图模型未返回有效图片" }
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
                val source = File(downloaded)
                val created = filesManager.moveImageFileTo(source, filePath)
                cleanupDownloadCheckpoint(downloaded)
                created
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
        requestId: String,
    ): File {
        val timestamp = System.currentTimeMillis()
        val stableRequestId = requestId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val target = File(
            filesManager.getImagesDir(),
            "imggen_${stableRequestId}_${index}.${item.mimeType.imageExtension()}",
        )
        val downloadedPath = item.localPath
        val materializeStartedAt = SystemClock.elapsedRealtime()
        val created = if (filesManager.isValidImageFile(target)) {
            downloadedPath?.let { path -> File(path).takeIf { it != target }?.delete() }
            target
        } else {
            item.materializeTo(target.absolutePath)
        }
        cleanupDownloadCheckpoint(downloadedPath)
        trace(
            traceId,
            "file_materialized",
            "index=$index source=${item.sourceForLog()} bytes=${created.length()} elapsed_ms=${elapsedSince(materializeStartedAt)}",
        )
        val databaseStartedAt = SystemClock.elapsedRealtime()
        genMediaRepository.insertMediaIfAbsent(
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

    /** A completed result no longer needs its resumable download checkpoint. */
    private fun cleanupDownloadCheckpoint(localPath: String?) {
        if (localPath.isNullOrBlank() || !localPath.endsWith(".part")) return
        val part = File(localPath)
        val metadata = File(part.parentFile, part.name.removeSuffix(".part") + ".meta")
        metadata.delete()
        File(metadata.parentFile, "${metadata.name}.tmp").delete()
    }

    private fun String.imageExtension(): String = when {
        contains("jpeg", ignoreCase = true) || contains("jpg", ignoreCase = true) -> "jpg"
        contains("webp", ignoreCase = true) -> "webp"
        else -> "png"
    }

    /**
     * The reported failure was `Software caused connection abort` mid-request — transient, and fatal
     * only because nothing retried it. Retries cover the request and the follow-up download of the
     * remote URL the gateway returns.
     */
    private suspend fun <T> withRetry(
        traceId: String,
        idempotent: () -> Boolean = { false },
        block: suspend (Committed, Int) -> T,
    ): T {
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
                Log.w(
                    TAG,
                    "Image request failed (attempt ${attempt + 1}/$MAX_ATTEMPTS): ${e.javaClass.simpleName}",
                )
                // Re-running after a partial success would insert duplicate gallery rows.
                if (committed.value) throw e
                // 上游按次计费: 请求一旦发出, 重试就是再花一次钱, 哪怕响应没读回来.
                // 所以只重试"根本没连上"的失败.
                if (!isSafeToRetry(e, idempotent())) throw e
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
    private fun isSafeToRetry(e: IOException, idempotent: Boolean): Boolean {
        if (idempotent) return true
        return when (e) {
        is SocketTimeoutException -> false
        is UnknownHostException, is ConnectException, is SSLHandshakeException -> true
        else -> false
        }
    }

    // 绘画特有的两种配置缺失自己处理, 其余(余额/限流/鉴权/网络...)交给共用的中文映射.
    private fun readableError(e: Exception): String = when {
        e.message?.contains("Cannot safely fall back to synchronous") == true ->
            "中转站暂时无法使用可恢复生图接口，请稍后重试或联系管理员。"
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

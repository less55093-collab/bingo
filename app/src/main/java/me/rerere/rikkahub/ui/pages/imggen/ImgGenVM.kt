package me.rerere.rikkahub.ui.pages.imggen

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.ai.ui.ImageGenSize
import me.rerere.common.android.imageGenerationInputFolder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.service.ImageGenerationManager
import me.rerere.rikkahub.ui.components.ai.IMAGE_GENERATION_PROGRESS_TICK_MS
import me.rerere.rikkahub.ui.components.ai.IMAGE_GENERATION_SLOW_HINT_MS
import me.rerere.rikkahub.ui.components.ai.estimateImageGenerationProgress
import java.io.File
import kotlin.uuid.Uuid

enum class ImageCreationMode {
    TEXT,
    REFERENCE,
}

internal fun canSubmitImageGeneration(
    mode: ImageCreationMode,
    prompt: String,
    referenceImageCount: Int,
): Boolean = prompt.isNotBlank() && (mode == ImageCreationMode.TEXT || referenceImageCount > 0)

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)

private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val imagesDir = filesManager.getImagesDir()
    val fullPath = File(imagesDir, this.path.removePrefix("images/")).absolutePath

    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId
    )
}

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
    private val imageGenerationManager: ImageGenerationManager,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _size = MutableStateFlow(ImageGenSize.AUTO.value)
    val size: StateFlow<String> = _size

    private val _mode = MutableStateFlow(ImageCreationMode.TEXT)
    val mode: StateFlow<ImageCreationMode> = _mode

    // 生成状态来自 ImageGenerationManager（AppScope），页面销毁不再中断生成
    val isGenerating: StateFlow<Boolean> = imageGenerationManager.state
        .map { it.generating }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val error: StateFlow<String?> = imageGenerationManager.state
        .map { it.error }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 估算进度(0f..1f)。上游不回传真实进度，所以按耗时推一条渐近曲线：越接近预期时长增长越慢，
     * 且永远到不了 1f，最后一段留给真实的完成事件收尾——否则进度条会先满上再干等着。
     * 由 [ImageGenerationManager.State.startedAt] 推算，因此退出页面再回来进度是连续的。
     */
    val progress: StateFlow<Float> = imageGenerationManager.state
        .flatMapLatest { state ->
            if (!state.generating || state.startedAt == 0L) {
                flowOf(0f)
            } else {
                flow {
                    while (true) {
                        val elapsed = SystemClock.elapsedRealtime() - state.startedAt
                        emit(estimateImageGenerationProgress(elapsed, editing = state.editing))
                        delay(IMAGE_GENERATION_PROGRESS_TICK_MS)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    /**
     * 生成已经超过 [IMAGE_GENERATION_SLOW_HINT_MS]，可以提示用户「可以退出，完成后会收到通知」。
     */
    val slowHint: StateFlow<Boolean> = imageGenerationManager.state
        .flatMapLatest { state ->
            if (!state.generating || state.startedAt == 0L) {
                flowOf(false)
            } else {
                flow {
                    while (true) {
                        emit(
                            SystemClock.elapsedRealtime() - state.startedAt >=
                                IMAGE_GENERATION_SLOW_HINT_MS
                        )
                        delay(IMAGE_GENERATION_PROGRESS_TICK_MS)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = imageGenerationManager.state
        .map { state ->
            state.images.map { image ->
                GeneratedImage(
                    id = 0,
                    prompt = state.prompt,
                    filePath = image.path,
                    timestamp = 0L,
                    model = "",
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() }
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateSize(size: String) {
        _size.value = size
    }

    fun updateMode(mode: ImageCreationMode) {
        if (imageGenerationManager.state.value.generating) return
        _mode.value = mode
    }

    fun addReferenceImages(paths: List<String>) {
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
    }

    fun removeReferenceImage(path: String) {
        if (imageGenerationManager.state.value.generating) return
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        if (imageGenerationManager.state.value.generating) return
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
    }

    fun clearError() {
        imageGenerationManager.clearError()
    }

    fun startNewSession() {
        if (imageGenerationManager.state.value.generating) return
        imageGenerationManager.reset()
        clearReferenceImages()
        _prompt.value = ""
        _mode.value = ImageCreationMode.TEXT
    }

    fun submitGeneration() {
        if (!canSubmitImageGeneration(_mode.value, _prompt.value, _referenceImages.value.size)) return
        when (_mode.value) {
            ImageCreationMode.TEXT -> generateImage()
            ImageCreationMode.REFERENCE -> editImage()
        }
    }

    fun generateImage() {
        imageGenerationManager.generate(
            prompt = _prompt.value,
            size = _size.value,
            numOfImages = _numberOfImages.value,
        )
    }

    fun editImage() {
        if (_referenceImages.value.isEmpty()) return
        imageGenerationManager.edit(
            prompt = _prompt.value,
            size = _size.value,
            numOfImages = _numberOfImages.value,
            referenceImages = _referenceImages.value,
        )
    }

    fun cancelGeneration() {
        imageGenerationManager.cancel()
    }

    fun startFromImage(image: GeneratedImage) {
        if (imageGenerationManager.state.value.generating) return
        viewModelScope.launch {
            val referencePath = withContext(Dispatchers.IO) {
                val source = File(image.filePath)
                if (!source.isFile) return@withContext null
                val target = File(
                    getApplication<Application>().imageGenerationInputFolder,
                    "imggen_ref_${Uuid.random()}.${source.extension.ifBlank { "png" }}",
                )
                source.copyTo(target, overwrite = true)
                target.absolutePath
            } ?: return@launch
            clearReferenceImages()
            _referenceImages.value = listOf(referencePath)
            _prompt.value = image.prompt
            _mode.value = ImageCreationMode.REFERENCE
        }
    }

    fun deleteImage(image: GeneratedImage) {
        viewModelScope.launch {
            try {
                // Delete from database first
                genMediaRepository.deleteMedia(image.id)

                // Then delete the file
                val file = File(image.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image", e)
            }
        }
    }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            paths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
    }
}

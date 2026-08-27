package me.rerere.rikkahub.ui.pages.imggen

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.ImageGenSize
import me.rerere.common.android.imageGenerationInputFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Colors
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ImageGenerationLoading
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ImageGenPage(
    modifier: Modifier = Modifier,
    vm: ImgGenVM = koinViewModel(),
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.imggen_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            vm.startNewSession()
                            scope.launch { pagerState.animateScrollToPage(0) }
                        },
                        enabled = !isGenerating,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.imggen_page_new_creation),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.imggen_page_create)) },
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.imggen_page_gallery)) },
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> ImageGenScreen(vm = vm)
                    1 -> ImageGalleryScreen(
                        vm = vm,
                        onCreate = { scope.launch { pagerState.animateScrollToPage(0) } },
                        onUseAsReference = { image ->
                            vm.startFromImage(image)
                            scope.launch { pagerState.animateScrollToPage(0) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CancelDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imggen_page_cancel_generation_title)) },
        text = { Text(stringResource(R.string.imggen_page_cancel_generation_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.imggen_page_cancel_generation_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.imggen_page_keep_generating))
            }
        },
    )
}

@Composable
private fun ImageGenScreen(vm: ImgGenVM) {
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val numberOfImages by vm.numberOfImages.collectAsStateWithLifecycle()
    val size by vm.size.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val slowHint by vm.slowHint.collectAsStateWithLifecycle()
    val currentGeneratedImages by vm.currentGeneratedImages.collectAsStateWithLifecycle()
    val referenceImages by vm.referenceImages.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val settings by vm.settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val selectedModel = settings.findModelById(settings.imageGenerationModelId)
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val insufficientBalanceMessage = stringResource(R.string.chat_error_insufficient_balance)
    val referenceImageFailedMessage = stringResource(R.string.imggen_page_reference_image_failed)
    val imagesSavedSuccessMessage = stringResource(R.string.imggen_page_images_saved_success)
    val saveFailedPrefix = stringResource(R.string.imggen_page_save_failed, "")
    val scrollState = rememberScrollState()
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showBalanceDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                scope.launch {
                    val paths = selectedUris.mapNotNull { uri ->
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048)
                                    ?: error("Failed to decode image")
                                val pngBytes = FileUtils.compressBitmapToPng(bitmap)
                                bitmap.recycle()
                                File(
                                    context.imageGenerationInputFolder,
                                    "imggen_ref_${Uuid.random()}.png",
                                ).apply { writeBytes(pngBytes) }.absolutePath
                            }.getOrNull()
                        }
                    }
                    vm.addReferenceImages(paths)
                    if (paths.size < selectedUris.size) {
                        toaster.show(
                            message = referenceImageFailedMessage,
                            type = ToastType.Error,
                        )
                    }
                }
            }
        }

    LaunchedEffect(error) {
        error?.let { errorMessage ->
            if (errorMessage == insufficientBalanceMessage) {
                showBalanceDialog = true
            } else {
                toaster.show(message = errorMessage, type = ToastType.Error)
                vm.clearError()
            }
        }
    }

    if (showBalanceDialog) {
        AlertDialog(
            onDismissRequest = {
                showBalanceDialog = false
                vm.clearError()
            },
            title = { Text(stringResource(R.string.imggen_error_insufficient_balance_title)) },
            text = { Text(stringResource(R.string.imggen_error_insufficient_balance_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBalanceDialog = false
                        vm.clearError()
                        navController.navigate(Screen.Redeem)
                    },
                ) {
                    Text(stringResource(R.string.imggen_error_insufficient_balance_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBalanceDialog = false
                        vm.clearError()
                    },
                ) {
                    Text(stringResource(R.string.imggen_page_cancel))
                }
            },
        )
    }

    if (showCancelDialog) {
        CancelDialog(
            onDismiss = { showCancelDialog = false },
            onConfirm = {
                showCancelDialog = false
                vm.cancelGeneration()
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ImageModeSelector(
            selectedMode = mode,
            enabled = !isGenerating,
            onSelect = vm::updateMode,
        )

        if (mode == ImageCreationMode.REFERENCE) {
            ReferenceImageSection(
                images = referenceImages,
                enabled = !isGenerating,
                onAdd = { imagePickerLauncher.launch("image/*") },
                onRemove = vm::removeReferenceImage,
            )
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = vm::updatePrompt,
            enabled = !isGenerating,
            label = { Text(stringResource(R.string.imggen_page_prompt_label)) },
            placeholder = { Text(stringResource(R.string.imggen_page_prompt_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 128.dp, max = 220.dp),
            minLines = 4,
            maxLines = 8,
            shape = MaterialTheme.shapes.large,
        )

        GenerationSummary(
            size = size,
            numberOfImages = numberOfImages,
            modelName = selectedModel?.displayName,
            enabled = !isGenerating,
            onClick = { showSettingsSheet = true },
        )

        if (selectedModel == null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.imggen_error_no_model),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showSettingsSheet = true }) {
                        Text(stringResource(R.string.imggen_page_select_model))
                    }
                }
            }
        }

        if (isGenerating) {
            OutlinedButton(
                onClick = { showCancelDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(HugeIcons.Cancel01, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.imggen_page_cancel_generation_action))
            }
        } else {
            Button(
                onClick = vm::submitGeneration,
                enabled = selectedModel != null &&
                    canSubmitImageGeneration(mode, prompt, referenceImages.size),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(HugeIcons.Colors, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.imggen_page_generate_image))
            }
        }

        Text(
            text = stringResource(R.string.imggen_page_background_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        when {
            isGenerating -> {
                ImageGenerationLoading(
                    progress = progress,
                    showSlowHint = slowHint,
                    loading = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                )
            }

            currentGeneratedImages.isNotEmpty() -> {
                GeneratedResults(
                    images = currentGeneratedImages,
                    size = size,
                    onSaveAll = {
                        scope.launch {
                            runCatching {
                                currentGeneratedImages.forEach { image ->
                                    filesManager.saveMessageImage(context, image.filePath)
                                }
                            }.onSuccess {
                                toaster.show(
                                    message = imagesSavedSuccessMessage,
                                    type = ToastType.Success,
                                )
                            }.onFailure { failure ->
                                toaster.show(
                                    message = saveFailedPrefix + failure.message.orEmpty(),
                                    type = ToastType.Error,
                                )
                            }
                        }
                    },
                    onContinue = {
                        vm.startFromImage(
                            currentGeneratedImages.first(),
                            onFailure = {
                                toaster.show(
                                    message = referenceImageFailedMessage,
                                    type = ToastType.Error,
                                )
                            },
                        )
                        scope.launch { scrollState.animateScrollTo(0) }
                    },
                )
            }

            else -> {
                Text(
                    text = stringResource(R.string.imggen_page_result_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            vm = vm,
            settings = settings,
            hasSelectedModel = selectedModel != null,
            numberOfImages = numberOfImages,
            size = size,
            scope = scope,
            sheetState = sheetState,
            onDismiss = { showSettingsSheet = false },
        )
    }
}

@Composable
private fun ImageModeSelector(
    selectedMode: ImageCreationMode,
    enabled: Boolean,
    onSelect: (ImageCreationMode) -> Unit,
) {
    val modes = listOf(
        ImageCreationMode.TEXT to stringResource(R.string.imggen_page_mode_text),
        ImageCreationMode.REFERENCE to stringResource(R.string.imggen_page_mode_reference),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onSelect(mode) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun ReferenceImageSection(
    images: List<String>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.imggen_page_reference_images),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.imggen_page_reference_images_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (images.isNotEmpty()) {
            ReferenceImagesRow(
                images = images,
                onRemove = onRemove,
                enabled = enabled,
            )
        }
        OutlinedButton(
            onClick = onAdd,
            enabled = enabled && images.size < 16,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Add01, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (images.isEmpty()) {
                    stringResource(R.string.imggen_page_add_reference_images)
                } else {
                    stringResource(R.string.imggen_page_add_more_reference_images)
                },
            )
        }
    }
}

@Composable
private fun ReferenceImagesRow(
    images: List<String>,
    onRemove: (String) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEachIndexed { index, image ->
            Surface(
                modifier = Modifier.size(72.dp),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box {
                    AsyncImage(
                        model = File(image),
                        contentDescription = stringResource(
                            R.string.imggen_page_reference_image_content_description,
                            index + 1,
                        ),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Surface(
                        onClick = { onRemove(image) },
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(28.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.imggen_page_remove_reference_image),
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenerationSummary(
    size: String,
    numberOfImages: Int,
    modelName: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.Tools, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.imggen_page_parameter_summary,
                        imageSizeLabel(size),
                        numberOfImages,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = modelName ?: stringResource(R.string.imggen_page_default_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = stringResource(R.string.imggen_page_adjust_parameters),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GeneratedResults(
    images: List<GeneratedImage>,
    size: String,
    onSaveAll: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.imggen_page_results),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        GeneratedImagesGrid(images = images, aspectRatio = imageAspectRatio(size))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onSaveAll,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.FloppyDisk, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.imggen_page_save_all))
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Add01, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (images.size == 1) {
                        stringResource(R.string.imggen_page_continue_editing)
                    } else {
                        stringResource(R.string.imggen_page_continue_with_first)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun GeneratedImagesGrid(
    images: List<GeneratedImage>,
    aspectRatio: Float,
) {
    images.chunked(2).forEachIndexed { rowIndex, rowImages ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowImages.forEachIndexed { index, image ->
                val imageNumber = rowIndex * 2 + index + 1
                var showPreview by remember(image.filePath) { mutableStateOf(false) }
                AsyncImage(
                    model = File(image.filePath),
                    contentDescription = stringResource(
                        R.string.imggen_page_generated_image_content_description,
                        imageNumber,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(aspectRatio)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { showPreview = true },
                    contentScale = ContentScale.Fit,
                )
                if (showPreview) {
                    ImagePreviewDialog(
                        images = listOf(image.filePath),
                        onDismissRequest = { showPreview = false },
                    )
                }
            }
            if (rowImages.size == 1 && images.size > 1) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ImageGalleryScreen(
    vm: ImgGenVM,
    onCreate: () -> Unit,
    onUseAsReference: (GeneratedImage) -> Unit,
) {
    val generatedImages = vm.generatedImages.collectAsLazyPagingItems()
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val imageSavedSuccessMessage = stringResource(R.string.imggen_page_image_saved_success)
    val saveFailedPrefix = stringResource(R.string.imggen_page_save_failed, "")
    val promptCopiedMessage = stringResource(R.string.imggen_page_prompt_copied)
    val pullToRefreshState = rememberPullToRefreshState()
    var selectedImage by remember { mutableStateOf<GeneratedImage?>(null) }
    var deleteTarget by remember { mutableStateOf<GeneratedImage?>(null) }

    fun saveImage(image: GeneratedImage) {
        scope.launch {
            runCatching { filesManager.saveMessageImage(context, image.filePath) }
                .onSuccess {
                    toaster.show(
                        message = imageSavedSuccessMessage,
                        type = ToastType.Success,
                    )
                }
                .onFailure { failure ->
                    toaster.show(
                        message = saveFailedPrefix + failure.message.orEmpty(),
                        type = ToastType.Error,
                    )
                }
        }
    }

    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { generatedImages.refresh() },
        state = pullToRefreshState,
    ) {
        when {
            generatedImages.loadState.refresh is LoadState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            generatedImages.loadState.refresh is LoadState.Error && generatedImages.itemCount == 0 -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.imggen_page_gallery_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = generatedImages::retry) {
                            Text(stringResource(R.string.imggen_page_retry))
                        }
                    }
                }
            }

            generatedImages.itemCount == 0 -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Image03,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.imggen_page_no_generated_images),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.imggen_page_gallery_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onCreate) {
                        Text(stringResource(R.string.imggen_page_start_creating))
                    }
                }
            }
            }

            else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(HugeIcons.Image03, contentDescription = null)
                        Text(
                            text = stringResource(R.string.imggen_page_not_backed_up),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 144.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(
                        count = generatedImages.itemCount,
                        key = generatedImages.itemKey { it.id },
                        contentType = generatedImages.itemContentType { "GeneratedImage" },
                    ) { index ->
                        generatedImages[index]?.let { image ->
                            GalleryImageTile(
                                image = image,
                                onClick = { selectedImage = image },
                            )
                        }
                    }
                }
            }
            }
        }
    }

    selectedImage?.let { image ->
        GalleryImageDetailsSheet(
            image = image,
            onDismiss = { selectedImage = null },
            onSave = { saveImage(image) },
            onCopyPrompt = {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("image prompt", image.prompt)),
                    )
                    toaster.show(
                        message = promptCopiedMessage,
                        type = ToastType.Success,
                    )
                }
            },
            onUseAsReference = {
                selectedImage = null
                onUseAsReference(image)
            },
            onDelete = {
                selectedImage = null
                deleteTarget = image
            },
        )
    }

    deleteTarget?.let { image ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.imggen_page_delete_image_title)) },
            text = { Text(stringResource(R.string.imggen_page_delete_image_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteImage(image)
                        selectedImage = null
                        deleteTarget = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.imggen_page_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.imggen_page_cancel))
                }
            },
        )
    }
}

@Composable
private fun GalleryImageTile(
    image: GeneratedImage,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            AsyncImage(
                model = File(image.filePath),
                contentDescription = image.prompt,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentScale = ContentScale.Crop,
            )
            Text(
                text = image.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun GalleryImageDetailsSheet(
    image: GeneratedImage,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onCopyPrompt: () -> Unit,
    onUseAsReference: () -> Unit,
    onDelete: () -> Unit,
) {
    val formattedTime = remember(image.timestamp) {
        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(image.timestamp))
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AsyncImage(
                model = File(image.filePath),
                contentDescription = image.prompt,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = image.prompt,
                style = MaterialTheme.typography.bodyLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (image.model.isNotBlank()) {
                    Text(
                        text = image.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onUseAsReference,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.imggen_page_use_as_reference))
            }
            OutlinedButton(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.FloppyDisk, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.imggen_page_save))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onCopyPrompt) {
                    Icon(HugeIcons.Copy01, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.imggen_page_copy_prompt))
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.imggen_page_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsBottomSheet(
    vm: ImgGenVM,
    settings: Settings,
    hasSelectedModel: Boolean,
    numberOfImages: Int,
    size: String,
    scope: CoroutineScope,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(!hasSelectedModel) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.imggen_page_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            SettingsSection(
                title = stringResource(R.string.imggen_page_generation_count),
                description = stringResource(R.string.imggen_page_generation_count_desc),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    (1..4).forEachIndexed { index, count ->
                        SegmentedButton(
                            selected = numberOfImages == count,
                            onClick = { vm.updateNumberOfImages(count) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                        ) {
                            Text(count.toString())
                        }
                    }
                }
            }

            SettingsSection(
                title = stringResource(R.string.imggen_page_aspect_ratio),
                description = stringResource(R.string.imggen_page_aspect_ratio_desc),
            ) {
                val commonSizes = listOf(
                    ImageGenSize.AUTO,
                    ImageGenSize.SQUARE_1024,
                    ImageGenSize.LANDSCAPE_1536,
                    ImageGenSize.PORTRAIT_1536,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    commonSizes.forEach { option ->
                        FilterChip(
                            selected = size == option.value,
                            onClick = { vm.updateSize(option.value) },
                            label = { Text(imageSizeLabel(option.value)) },
                        )
                    }
                }
            }

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(
                    if (showAdvanced) {
                        stringResource(R.string.imggen_page_hide_advanced)
                    } else {
                        stringResource(R.string.imggen_page_show_advanced)
                    },
                )
            }

            if (showAdvanced) {
                SettingsSection(title = stringResource(R.string.imggen_page_model_selection)) {
                    ModelSelector(
                        modelId = settings.imageGenerationModelId,
                        providers = settings.providers,
                        type = ModelType.IMAGE,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { model ->
                            scope.launch {
                                vm.settingsStore.update { oldSettings ->
                                    oldSettings.copy(imageGenerationModelId = model.id)
                                }
                            }
                        },
                    )
                }
                SettingsSection(title = stringResource(R.string.imggen_page_custom_size)) {
                    OutlinedTextField(
                        value = size,
                        onValueChange = vm::updateSize,
                        placeholder = { Text(stringResource(R.string.imggen_page_custom_size_example)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
private fun imageSizeLabel(size: String): String = when (size) {
    ImageGenSize.AUTO.value -> stringResource(R.string.imggen_page_size_auto)
    ImageGenSize.SQUARE_1024.value -> stringResource(R.string.imggen_page_size_square)
    ImageGenSize.LANDSCAPE_1536.value -> stringResource(R.string.imggen_page_size_landscape)
    ImageGenSize.PORTRAIT_1536.value -> stringResource(R.string.imggen_page_size_portrait)
    else -> size
}

internal fun imageAspectRatio(size: String): Float {
    if (size == ImageGenSize.AUTO.value) return 1f
    val parts = size.lowercase().split('x')
    if (parts.size != 2) return 1f
    val width = parts[0].toFloatOrNull() ?: return 1f
    val height = parts[1].toFloatOrNull()?.takeIf { it > 0f } ?: return 1f
    if (width <= 0f) return 1f
    return (width / height).coerceIn(0.4f, 2.5f)
}

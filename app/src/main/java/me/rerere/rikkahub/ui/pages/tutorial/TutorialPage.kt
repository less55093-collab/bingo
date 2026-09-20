package me.rerere.rikkahub.ui.pages.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R

@Composable
fun TutorialPage(isLoggedIn: Boolean = false, onComplete: () -> Unit) {
    val steps = TutorialSteps
    val pagerState = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<TutorialImage?>(null) }

    BackHandler {
        if (pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        } else {
            onComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tutorial_page_title)) },
                actions = {
                    TextButton(onClick = onComplete) {
                        Text(stringResource(if (isLoggedIn) R.string.tutorial_close else R.string.tutorial_skip))
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clearAndSetSemantics {},
                ) {
                    steps.indices.forEach { index ->
                        Surface(
                            modifier = Modifier.size(if (index == pagerState.currentPage) 9.dp else 7.dp),
                            shape = CircleShape,
                            color = if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                        ) {}
                    }
                }
                Row(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (pagerState.currentPage > 0) {
                        OutlinedButton(
                            onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                            enabled = !pagerState.isScrollInProgress,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.tutorial_prev)) }
                    }
                    Button(
                        onClick = {
                            if (pagerState.currentPage == steps.lastIndex) onComplete()
                            else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        },
                        enabled = !pagerState.isScrollInProgress,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(stringResource(
                            if (pagerState.currentPage != steps.lastIndex) R.string.tutorial_next
                            else if (isLoggedIn) R.string.tutorial_return
                            else R.string.tutorial_done,
                        ))
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalAlignment = Alignment.Top,
        ) { page ->
            TutorialStepContent(steps[page], page, steps.size, onImageClick = { detail = it })
        }
    }

    detail?.let { image -> TutorialImageDetail(image, onDismiss = { detail = null }) }
}

@Composable
private fun TutorialStepContent(
    step: TutorialStep,
    index: Int,
    count: Int,
    onImageClick: (TutorialImage) -> Unit,
) {
    var selectedImage by rememberSaveable(step.title) { mutableIntStateOf(0) }
    val image = step.images[selectedImage]
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.tutorial_shop_progress, index + 1, count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(step.title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(step.body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Image(
            painter = painterResource(image.preview),
            contentDescription = stringResource(image.label),
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 390.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.tutorial_view_image),
                ) { onImageClick(image) },
        )

        if (step.images.size > 1) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                step.images.forEachIndexed { imageIndex, item ->
                    FilterChip(
                        selected = selectedImage == imageIndex,
                        onClick = { selectedImage = imageIndex },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        }
        TextButton(onClick = { onImageClick(image) }) { Text(stringResource(R.string.tutorial_view_image)) }
        Text(
            stringResource(step.instruction),
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(bottom = 24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TutorialImageDetail(image: TutorialImage, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(image.label)) },
                    actions = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tutorial_close)) } },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                Image(
                    painter = painterResource(image.detail),
                    contentDescription = stringResource(image.label),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                )
            }
        }
    }
}

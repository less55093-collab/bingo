package me.rerere.rikkahub.ui.pages.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun TutorialPage(onComplete: () -> Unit) {
    val steps = TutorialSteps
    val pagerState = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tutorial_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = onComplete) { Text(stringResource(R.string.tutorial_skip)) }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                TutorialStepContent(
                    step = steps[page],
                        onAction = {},
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                steps.indices.forEach { index ->
                    val selected = index == pagerState.currentPage
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (selected) 9.dp else 7.dp),
                        shape = CircleShape,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ) {}
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (pagerState.currentPage > 0) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.tutorial_prev))
                    }
                }
                if (pagerState.currentPage < steps.lastIndex) {
                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.tutorial_next))
                    }
                } else {
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.tutorial_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialStepContent(
    step: TutorialStep,
    onAction: (TutorialAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TutorialChatPreview(step.preview)

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(step.title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(step.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (step.action != TutorialAction.None && step.actionLabel != null) {
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = { onAction(step.action) }) {
                Text(stringResource(step.actionLabel), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TutorialChatPreview(preview: TutorialPreview) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 312.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "新对话",
                style = MaterialTheme.typography.labelLarge,
            )
            TutorialBubble(
                text = when (preview) {
                    TutorialPreview.Idea -> "我想做一张咖啡店开业海报，感觉温暖一点"
                    TutorialPreview.Plan -> "先给我 3 个不同风格的方案，我选好后再生成"
                    TutorialPreview.Result -> "就用温暖手作风，生成吧"
                },
                mine = true,
            )
            when (preview) {
                TutorialPreview.Idea -> {
                    TutorialBubble(
                        text = "收到。我会帮你确定主体、风格和画面比例。",
                        mine = false,
                    )
                    TutorialDetail("温暖色调  ·  咖啡香气  ·  开业信息区")
                }
                TutorialPreview.Plan -> {
                    Text("为你准备了 3 个画面方向", style = MaterialTheme.typography.labelLarge)
                    TutorialPlanRow("温暖手作风", "咖啡、木质和手写感", selected = true)
                    TutorialPlanRow("极简现代风", "留白、几何和清晰信息", selected = false)
                    TutorialPlanRow("夜间霓虹风", "城市感和明亮灯光", selected = false)
                }
                TutorialPreview.Result -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(136.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("COFFEE OPENING", style = MaterialTheme.typography.titleMedium)
                            Text("Warm. Fresh. Yours.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    TutorialDetail("已按温暖手作风生成")
                }
            }
        }
    }
}

@Composable
private fun TutorialBubble(text: String, mine: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TutorialPlanRow(title: String, detail: String, selected: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(9.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(detail, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TutorialDetail(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

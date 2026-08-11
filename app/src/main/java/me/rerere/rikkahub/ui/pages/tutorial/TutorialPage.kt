package me.rerere.rikkahub.ui.pages.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl

@Composable
fun TutorialPage() {
    val steps = TutorialSteps
    val pagerState = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tutorial_page_title)) },
                navigationIcon = { BackButton() },
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
                    onAction = { action ->
                        when (action) {
                            TutorialAction.OpenShop -> context.openUrl(BingoGatewayAPI.SHOP_URL)
                            TutorialAction.GoRedeem -> navController.navigate(Screen.Redeem)
                            TutorialAction.None -> Unit
                        }
                    },
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
                        onClick = { navController.popBackStack() },
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
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = step.icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

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

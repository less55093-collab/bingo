package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.modifier.smoke
import kotlin.math.exp
import kotlin.time.Clock

internal const val IMAGE_GENERATION_PROGRESS_TICK_MS = 100L
internal const val IMAGE_GENERATION_SLOW_HINT_MS = 10_000L

private const val EXPECTED_GENERATION_DURATION_MS = 40_000f
private const val EXPECTED_EDIT_DURATION_MS = 75_000f
private const val IMAGE_GENERATION_PROGRESS_CEILING = 0.95f
private const val IMAGE_GENERATION_PROGRESS_ANIMATION_MS = 100

internal fun estimateImageGenerationProgress(
    elapsedMillis: Long,
    editing: Boolean = false,
): Float {
    val scale = if (editing) EXPECTED_EDIT_DURATION_MS else EXPECTED_GENERATION_DURATION_MS
    val elapsedScale = elapsedMillis.coerceAtLeast(0L) / scale
    val eased = 1f - exp(-2.2f * elapsedScale)
    return (eased * IMAGE_GENERATION_PROGRESS_CEILING)
        .coerceIn(0f, IMAGE_GENERATION_PROGRESS_CEILING)
}

internal data class RememberedImageGenerationProgress(
    val value: Float,
    val showSlowHint: Boolean,
)

/** Keeps each chat tool's progress clock isolated and restorable by tool call id. */
@Composable
internal fun rememberImageGenerationProgress(
    toolCallId: String,
    running: Boolean,
): RememberedImageGenerationProgress {
    val startedAtMillis = rememberSaveable(toolCallId) { Clock.System.now().toEpochMilliseconds() }
    var elapsedMillis by rememberSaveable(toolCallId) {
        mutableLongStateOf(
            (Clock.System.now().toEpochMilliseconds() - startedAtMillis).coerceAtLeast(0L)
        )
    }

    LaunchedEffect(toolCallId, running) {
        while (running) {
            elapsedMillis =
                (Clock.System.now().toEpochMilliseconds() - startedAtMillis).coerceAtLeast(0L)
            delay(IMAGE_GENERATION_PROGRESS_TICK_MS)
        }
    }

    return remember(elapsedMillis) {
        RememberedImageGenerationProgress(
            value = estimateImageGenerationProgress(elapsedMillis),
            showSlowHint = running && elapsedMillis >= IMAGE_GENERATION_SLOW_HINT_MS,
        )
    }
}

@Composable
internal fun ImageGenerationLoading(
    progress: Float,
    showSlowHint: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = IMAGE_GENERATION_PROGRESS_ANIMATION_MS),
        label = "image_generation_progress",
    )

    Box(
        modifier = modifier.smoke(
            isActive = loading,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            if (loading) {
                CircularWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(
                text = if (loading) {
                    stringResource(
                        R.string.chat_message_image_generation_loading_progress,
                        (animatedProgress * 100).toInt(),
                    )
                } else {
                    stringResource(R.string.chat_message_image_generation_failed)
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            AnimatedVisibility(visible = loading && showSlowHint) {
                Text(
                    text = stringResource(R.string.chat_message_image_generation_long_wait),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

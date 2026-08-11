package me.rerere.rikkahub.ui.modifier

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 一团烟雾的运动参数. 每团烟的漂移轨迹是两条不同频率的正弦叠加, 频率取整数,
 * 这样动画进度从 1 回绕到 0 时相位刚好连续, 不会出现跳帧.
 *
 * @param driftX 水平漂移幅度, 相对于组件较短边的比例
 * @param driftY 垂直漂移幅度, 相对于组件较短边的比例
 * @param freqX 水平方向的往复次数 (整数, 保证无缝循环)
 * @param freqY 垂直方向的往复次数 (整数, 保证无缝循环)
 * @param phase 初始相位偏移, 让各团烟错开而不是同步移动
 * @param radius 烟团半径, 相对于组件较短边的比例
 * @param alpha 该团烟的最大不透明度
 */
private data class SmokePuff(
    val driftX: Float,
    val driftY: Float,
    val freqX: Int,
    val freqY: Int,
    val phase: Float,
    val radius: Float,
    val alpha: Float,
)

/**
 * 频率两两不同, 相位错开, 叠加后看不出单个烟团的周期, 整体像在缓慢翻卷.
 */
private val SMOKE_PUFFS = listOf(
    SmokePuff(driftX = 0.22f, driftY = 0.14f, freqX = 1, freqY = 2, phase = 0.00f, radius = 0.62f, alpha = 0.38f),
    SmokePuff(driftX = 0.30f, driftY = 0.10f, freqX = 2, freqY = 1, phase = 0.35f, radius = 0.50f, alpha = 0.30f),
    SmokePuff(driftX = 0.16f, driftY = 0.24f, freqX = 1, freqY = 3, phase = 0.62f, radius = 0.70f, alpha = 0.26f),
    SmokePuff(driftX = 0.26f, driftY = 0.18f, freqX = 3, freqY = 2, phase = 0.85f, radius = 0.44f, alpha = 0.22f),
)

private const val TWO_PI = (Math.PI * 2).toFloat()

/**
 * 在内容下方绘制一层缓慢翻卷的烟雾, 用于生成中的等待状态.
 *
 * 和 [shimmer] 的区别: shimmer 用 DstIn 把内容本身当作遮罩, 适合文字骨架屏;
 * 这里是在内容背后堆几团半透明的径向渐变, 内容保持清晰, 适合占位画布.
 *
 * @param isActive 是否播放动效. 传 false 时完全不绘制, 也不会启动动画.
 * @param color 烟雾颜色, 默认跟随当前内容色.
 * @param durationMillis 一个完整循环的时长, 越长越像"缭绕"而不是"闪动".
 */
@Composable
fun Modifier.smoke(
    isActive: Boolean,
    color: Color = LocalContentColor.current,
    durationMillis: Int = 9000,
): Modifier {
    if (!isActive) return this

    val transition = rememberInfiniteTransition(label = "SmokeTransition")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "SmokePhase",
    )

    return this.drawBehind {
        val shorterSide = min(size.width, size.height)
        if (shorterSide <= 0f) return@drawBehind

        SMOKE_PUFFS.forEach { puff ->
            val angleX = TWO_PI * (puff.freqX * phase + puff.phase)
            val angleY = TWO_PI * (puff.freqY * phase + puff.phase)
            val center = Offset(
                x = size.width / 2f + sin(angleX) * puff.driftX * shorterSide,
                y = size.height / 2f + cos(angleY) * puff.driftY * shorterSide,
            )
            // 半径也随相位轻微呼吸, 避免看起来像一个刚体在平移
            val radius = puff.radius * shorterSide * (1f + 0.18f * sin(angleX + angleY))
            if (radius <= 0f) return@forEach

            drawCircle(
                brush = Brush.radialGradient(
                    // 中心到边缘渐隐, 中段留一个较缓的过渡, 边界才不会有硬圈
                    colorStops = arrayOf(
                        0.0f to color.copy(alpha = puff.alpha),
                        0.45f to color.copy(alpha = puff.alpha * 0.55f),
                        1.0f to Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

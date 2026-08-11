package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/**
 * [data] 是 base64; 当上游只回远端 URL 时改走 [localPath], 图片已经下载到本地临时文件,
 * 此时 [data] 为空. 之所以不统一成 base64: 一张几 MB 的图编码再解码要多分配十几 MB,
 * 低端机上是实打实的 OOM 风险, 而消费方本来就只是把它写成文件.
 */
@Serializable
data class ImageGenerationItem(
    val data: String = "",
    val mimeType: String,
    val partial: Boolean = false,
    val partialImageIndex: Int? = null,
    val localPath: String? = null,
)

@Serializable
enum class ImageGenSize(val value: String) {
    AUTO("auto"),
    SQUARE_1024("1024x1024"),
    LANDSCAPE_1536("1536x1024"),
    PORTRAIT_1536("1024x1536"),
    SQUARE_256("256x256"),
    SQUARE_512("512x512"),
    LANDSCAPE_1792("1792x1024"),
    PORTRAIT_1792("1024x1792"),
}

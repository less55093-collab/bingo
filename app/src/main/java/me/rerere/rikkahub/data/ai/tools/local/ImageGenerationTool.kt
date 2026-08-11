package me.rerere.rikkahub.data.ai.tools.local

import androidx.core.net.toUri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ImageGenerationManager

private val ALLOWED_SIZES = listOf(
    ImageGenSize.AUTO.value,
    ImageGenSize.SQUARE_1024.value,
    ImageGenSize.LANDSCAPE_1536.value,
    ImageGenSize.PORTRAIT_1536.value,
)

/**
 * Lets the model draw pictures mid-conversation, so "画一只猫" no longer means leaving the chat for
 * the dedicated image page. That page's button stays — this only adds a second, language-driven entry.
 *
 * Generation runs through [ImageGenerationManager], the same application-scoped pipeline the standalone
 * page uses. The finished images are returned as [UIMessagePart.Image], which the existing tool renderer
 * already displays inline.
 */
fun buildImageGenerationTool(manager: ImageGenerationManager): Tool = Tool(
    name = "generate_image",
    description = """
        Generate an image from a text description.
        Use this whenever the user asks you to draw, paint, illustrate, design or otherwise produce
        a picture, and when a visual would answer the request better than words.

        Guidelines:
        - Write `prompt` as a single self-contained English description of the final picture, including
          subject, setting, composition, lighting and style. The user's own wording is usually too
          short: expand it, but never invent requirements they would object to.
        - Pick `size` from the shape of the subject: portraits and posters are tall, scenery and
          banners are wide, everything else can stay square or auto.
        - Generating takes a while. Call this once per requested picture, and do not retry a call that
          already returned an image.
        - The image is shown to the user automatically. Afterwards describe what you made in one or
          two sentences instead of repeating the prompt or pasting any file path.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Detailed English description of the image to generate.")
                })
                put("size", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Aspect ratio: `1024x1024` square, `1536x1024` landscape, `1024x1536` " +
                            "portrait, or `auto` to let the model decide. Defaults to `auto`."
                    )
                    put("enum", JsonArray(ALLOWED_SIZES.map { JsonPrimitive(it) }))
                })
            },
            required = listOf("prompt"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("`prompt` is required")
        val size = obj["size"]?.jsonPrimitive?.contentOrNull?.takeIf { it in ALLOWED_SIZES }
            ?: ImageGenSize.AUTO.value

        val files = manager.generateForTool(prompt = prompt, size = size)
        if (files.isEmpty()) error("image generation returned no image")

        files.map { UIMessagePart.Image(url = it.toUri().toString()) }
    },
)

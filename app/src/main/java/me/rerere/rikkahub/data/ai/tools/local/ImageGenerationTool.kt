package me.rerere.rikkahub.data.ai.tools.local

import androidx.core.net.toUri
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ImageGenerationManager

const val IMAGE_GENERATION_TOOL_NAME = "generate_image"
const val IMAGE_GENERATION_PLAN_TOOL_NAME = "plan_image_generation"

private val ALLOWED_SIZES = listOf(
    ImageGenSize.AUTO.value,
    ImageGenSize.SQUARE_1024.value,
    ImageGenSize.LANDSCAPE_1536.value,
    ImageGenSize.PORTRAIT_1536.value,
)

data class ImageGenerationVariant(
    val label: String,
    val prompt: String,
    val size: String,
)

internal fun parseImageGenerationVariants(args: JsonElement): List<ImageGenerationVariant> {
    val variants = args.jsonObject["variants"] ?: return emptyList()
    return variants.jsonArray.mapIndexed { index, element ->
        val variant = element.jsonObject
        val prompt = variant["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("`variants[$index].prompt` is required")
        val size = variant["size"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in ALLOWED_SIZES }
            ?: ImageGenSize.AUTO.value
        ImageGenerationVariant(
            label = variant["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "Variant ${index + 1}",
            prompt = prompt,
            size = size,
        )
    }
}

internal fun directImageGenerationNeedsApproval(@Suppress("UNUSED_PARAMETER") args: JsonElement): Boolean = false

internal fun planImageGenerationNeedsApproval(args: JsonElement): Boolean =
    runCatching { parseImageGenerationVariants(args).size > 1 }.getOrDefault(false)

private fun imageProperties(includeVariants: Boolean): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("prompt", buildJsonObject {
        put("type", "string")
        put("description", "Concise Chinese description of the image to generate.")
    })
    put("size", buildJsonObject {
        put("type", "string")
        put(
            "description",
            "Aspect ratio: `1024x1024` square, `1536x1024` landscape, `1024x1536` portrait, or `auto`."
        )
        put("enum", JsonArray(ALLOWED_SIZES.map { JsonPrimitive(it) }))
    })
    if (includeVariants) put("variants", variantsSchema())
}

private fun variantsSchema() = buildJsonObject {
    put("type", "array")
    put(
        "description",
        "Two or more alternative image directions. Use only for multiple subjects, styles, versions, or images."
    )
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("label", buildJsonObject {
                put("type", "string")
                put("description", "Short Chinese name for this visual direction.")
            })
            put("prompt", buildJsonObject {
                put("type", "string")
                put("description", "Concise Chinese description of this image variant.")
            })
            put("size", buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(ALLOWED_SIZES.map { JsonPrimitive(it) }))
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
    })
}

internal fun referenceImagePathsFromMessages(messages: List<me.rerere.ai.ui.UIMessage>): List<String> {
    fun localImagePaths(parts: List<UIMessagePart>): List<String> = parts
        .filterIsInstance<UIMessagePart.Image>()
        .mapNotNull { part ->
            val uri = runCatching { java.net.URI(part.url) }.getOrNull()
            if (uri?.scheme != "file") return@mapNotNull null
            val path = uri.path ?: return@mapNotNull null
            File(path).takeIf { it.isFile }?.absolutePath
        }
        .distinct()

    val latestUserImages = messages.asReversed()
        .firstOrNull { it.role == MessageRole.USER }
        ?.parts
        ?.let(::localImagePaths)
        .orEmpty()
    if (latestUserImages.isNotEmpty()) return latestUserImages

    messages.asReversed().forEach { message ->
        if (message.role != MessageRole.ASSISTANT) return@forEach
        message.parts.asReversed()
            .filterIsInstance<UIMessagePart.Tool>()
            .firstOrNull { tool ->
                tool.toolName == IMAGE_GENERATION_TOOL_NAME ||
                    tool.toolName == IMAGE_GENERATION_PLAN_TOOL_NAME
            }
            ?.let { tool ->
                localImagePaths(tool.output).takeIf { it.isNotEmpty() }
            }
            ?.let { return it }
    }
    return emptyList()
}

private suspend fun generateImageParts(
    manager: ImageGenerationManager,
    prompt: String,
    size: String,
    referenceImages: List<String> = emptyList(),
): List<UIMessagePart> {
    val files = if (referenceImages.isEmpty()) {
        manager.generateForTool(prompt = prompt, size = size)
    } else {
        manager.editForTool(prompt = prompt, size = size, referenceImages = referenceImages)
    }
    if (files.isEmpty()) error("image generation returned no image")
    return files.map { UIMessagePart.Image(url = it.toUri().toString()) }
}

private suspend fun executePlanImageGeneration(
    manager: ImageGenerationManager,
    args: JsonElement,
    referenceImages: List<String> = emptyList(),
): List<UIMessagePart> {
    val obj = args.jsonObject
    val request = obj["request"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val parsedVariants = runCatching { parseImageGenerationVariants(args) }.getOrNull().orEmpty()
    return when {
        parsedVariants.size > 1 -> {
            val files = if (referenceImages.isEmpty()) {
                manager.generateForToolBatch(parsedVariants)
            } else {
                manager.editForToolBatch(parsedVariants, referenceImages)
            }
            if (files.isEmpty()) error("image generation returned no image")
            files.map { UIMessagePart.Image(url = it.toUri().toString()) }
        }
        parsedVariants.size == 1 -> generateImageParts(
            manager,
            parsedVariants.single().prompt,
            parsedVariants.single().size,
            referenceImages,
        )
        request != null -> generateImageParts(manager, request, ImageGenSize.AUTO.value, referenceImages)
        else -> error("`request` is required when image plan variants are missing or invalid")
    }
}

/** Direct single-image generation. Its input is intentionally independent of plan arguments. */
fun buildImageGenerationTool(manager: ImageGenerationManager): Tool = Tool(
    name = IMAGE_GENERATION_TOOL_NAME,
    description = """
        Generate one image directly when the user asks for a simple single-subject, single-style image.
        Always call this tool for one image. Do not add variants. Use concise Chinese prompts and preserve
        confirmed requirements. Choose a size that matches the use case. Do not retry completed calls.
    """.trimIndent(),
    systemPrompt = { _, messages -> ImagePromptRecipes.instructionFor(messages) },
    parameters = {
        InputSchema.Obj(
            properties = imageProperties(includeVariants = false),
            required = listOf("prompt"),
        )
    },
    needsApproval = ::directImageGenerationNeedsApproval,
    execute = { args ->
        val obj = args.jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("`prompt` is required")
        val size = obj["size"]?.jsonPrimitive?.contentOrNull?.takeIf { it in ALLOWED_SIZES }
            ?: ImageGenSize.AUTO.value
        generateImageParts(manager, prompt, size)
    },
    executeWithContext = { args, messages ->
        val obj = args.jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("`prompt` is required")
        val size = obj["size"]?.jsonPrimitive?.contentOrNull?.takeIf { it in ALLOWED_SIZES }
            ?: ImageGenSize.AUTO.value
        generateImageParts(manager, prompt, size, referenceImagePathsFromMessages(messages))
    },
)

/** Planning tool for requests that explicitly need alternatives or multiple images. */
fun buildPlanImageGenerationTool(manager: ImageGenerationManager): Tool = Tool(
    name = IMAGE_GENERATION_PLAN_TOOL_NAME,
    description = """
        Prepare image directions before generation only when the request has multiple subjects/scenes,
        multiple styles, multiple versions, or asks for more than one image. Return two or three useful
        Chinese variants and preserve the original request in `request`. The user will choose directions.
        For a simple single image, call generate_image instead. Do not use this tool for a poster or avatar
        merely because it has a use case.
    """.trimIndent(),
    systemPrompt = { _, messages -> ImagePromptRecipes.instructionFor(messages) },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("request", buildJsonObject {
                    put("type", "string")
                    put("description", "The user's original image request, used for safe fallback.")
                })
                put("variants", variantsSchema())
            },
            required = listOf("request", "variants"),
        )
    },
    needsApproval = ::planImageGenerationNeedsApproval,
    execute = { args -> executePlanImageGeneration(manager, args) },
    executeWithContext = { args, messages ->
        executePlanImageGeneration(manager, args, referenceImagePathsFromMessages(messages))
    },
)

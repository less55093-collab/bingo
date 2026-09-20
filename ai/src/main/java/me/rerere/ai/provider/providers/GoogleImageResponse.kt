package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ImageGenerationItem

internal fun parseGoogleImageResponse(body: JsonObject): List<ImageGenerationItem> =
    (body["candidates"] as? JsonArray).orEmpty().flatMap { candidate ->
        val content = (candidate as? JsonObject)?.get("content") as? JsonObject
        (content?.get("parts") as? JsonArray).orEmpty().mapNotNull { raw ->
            val part = raw as? JsonObject ?: return@mapNotNull null
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val inline = (part["inlineData"] ?: part["inline_data"]) as? JsonObject ?: return@mapNotNull null
            val mime = (inline["mimeType"] ?: inline["mime_type"])?.jsonPrimitive?.contentOrNull ?: "image/png"
            val data = inline["data"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (!mime.startsWith("image/")) return@mapNotNull null
            ImageGenerationItem(data = data, mimeType = mime)
        }
    }

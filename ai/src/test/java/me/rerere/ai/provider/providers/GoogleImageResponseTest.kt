package me.rerere.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class GoogleImageResponseTest {
    @Test fun `returns final images with their mime types and skips text and thought drafts`() {
        val response = Json.parseToJsonElement("""{"candidates":[{"content":{"parts":[
            {"text":"Here is your picture"},
            {"thought":true,"inlineData":{"mimeType":"image/png","data":"draft"}},
            {"inlineData":{"mimeType":"image/jpeg","data":"final-jpeg"}},
            {"inline_data":{"mime_type":"image/png","data":"final-png"}}
        ]}}]}""").jsonObject
        val images = parseGoogleImageResponse(response)
        assertEquals(listOf("final-jpeg", "final-png"), images.map { it.data })
        assertEquals(listOf("image/jpeg", "image/png"), images.map { it.mimeType })
    }

    @Test fun `blocked and text only responses do not fabricate successful images`() {
        assertTrue(parseGoogleImageResponse(Json.parseToJsonElement("""{"promptFeedback":{"blockReason":"SAFETY"}}""").jsonObject).isEmpty())
        assertTrue(parseGoogleImageResponse(Json.parseToJsonElement("""{"candidates":[{"content":{"parts":[{"text":"No image"}]}}]}""").jsonObject).isEmpty())
    }
}

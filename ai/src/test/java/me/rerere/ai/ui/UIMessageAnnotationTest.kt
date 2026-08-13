package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessageAnnotationTest {
    @Test
    fun `generation interrupted annotation survives message json round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val message = UIMessage.assistant("partial reply").copy(
            annotations = listOf(
                UIMessageAnnotation.UrlCitation(title = "Source", url = "https://example.com"),
                UIMessageAnnotation.GenerationInterrupted,
            ),
        )

        val encoded = json.encodeToString(message)
        val restored = json.decodeFromString<UIMessage>(encoded)

        assertTrue(encoded.contains("\"generation_interrupted\""))
        assertEquals(message.annotations, restored.annotations)
    }
}

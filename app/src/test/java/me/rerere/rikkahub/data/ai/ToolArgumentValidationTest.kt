package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolArgumentValidationTest {
    @Test fun `nested invalid types and excessive batch are rejected before tool execution`() {
        val schema = Json.parseToJsonElement("""{"type":"array","maxItems":3,"minItems":1,"items":{"type":"object","required":["prompt"],"properties":{"prompt":{"type":"string"}}}}""").jsonObject
        for (value in listOf("[]", "[{\"prompt\":3}]", "[{}]", "[{\"prompt\":\"a\"},{\"prompt\":\"b\"},{\"prompt\":\"c\"},{\"prompt\":\"d\"}]")) {
            assertThrows(IllegalArgumentException::class.java) { validateToolArgumentValue(Json.parseToJsonElement(value), schema) }
        }
        validateToolArgumentValue(Json.parseToJsonElement("""[{"prompt":"商品白底图"}]"""), schema)
    }

    @Test fun `duplicate billable calls rejected even with different protocol identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateExecutableToolCalls(listOf("one", "two").map {
                UIMessagePart.Tool(toolCallId = it, toolName = "generate_image", input = """{"prompt":"商品图"}""")
            })
        }
    }
}

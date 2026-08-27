package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertThrows
import org.junit.Test

class GenerationHandlerToolValidationTest {
    @Test
    fun `complete tool call is executable`() {
        validateExecutableToolCalls(
            listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search_web",
                    input = """{"query":"山东大学 分数线"}""",
                )
            )
        )
    }

    @Test
    fun `stream fragment without call id is rejected`() {
        val fragment = UIMessagePart.Tool(
            toolCallId = "",
            toolName = "search_web",
            input = """{"query":"山"}""",
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateExecutableToolCalls(listOf(fragment))
        }
    }
}

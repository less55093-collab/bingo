package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.*
import org.junit.Test

class ChatGenerationInputTest {
    private fun imageRequest() = UIMessage.user("电商产品图").copy(annotations = listOf(UIMessageAnnotation.DirectImageRequest))

    @Test fun `regenerating old chat ignores newer direct image mode`() {
        val chat = UIMessage.user("你好")
        val all = listOf(chat, UIMessage.assistant("你好"), imageRequest(), UIMessage.assistant(""))
        val input = selectChatGenerationInput(all, 0..0)
        assertEquals(listOf(chat), input.messages)
        assertFalse(input.directImageGeneration)
        assertTrue(selectChatGenerationInput(all, null).directImageGeneration)
    }

    @Test fun `regenerating old direct image keeps image mode despite newer chat`() {
        val direct = imageRequest()
        val all = listOf(direct, UIMessage.assistant(""), UIMessage.user("聊一下"))
        val input = selectChatGenerationInput(all, 0..0)
        assertEquals(listOf(direct), input.messages)
        assertTrue(input.directImageGeneration)
        assertFalse(selectChatGenerationInput(all, null).directImageGeneration)
    }

    @Test fun `stale message range is rejected rather than selecting other messages`() {
        val messages = listOf(UIMessage.user("hello"))
        assertTrue(runCatching { selectChatGenerationInput(messages, 0..2) }.isFailure)
        assertTrue(runCatching { selectChatGenerationInput(messages, -1..0) }.isFailure)
        assertEquals(emptyList<UIMessage>(), selectChatGenerationInput(messages, 0..-1).messages)
    }
}

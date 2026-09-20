package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectImageRequestTest {
    @Test
    fun `ordinary chat containing image keywords never creates a billable tool`() {
        val message = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("帮我分析生图方法")))
        assertNull(prepareDirectImageToolMessage(listOf(message), Model()))
    }

    @Test
    fun `explicit image request produces one approved tool using the exact user prompt`() {
        val prompt = "商品白底图，保留包装上的 \"Bingo\" 文字\n背景简洁"
        val message = directRequest(prompt)
        val assistant = prepareDirectImageToolMessage(listOf(message), Model())!!
        val tool = assistant.parts.single() as UIMessagePart.Tool

        assertEquals("generate_image", tool.toolName)
        assertEquals(prompt, tool.inputAsJson().jsonObject["prompt"]!!.jsonPrimitive.content)
        assertEquals(ToolApprovalState.Approved, tool.approvalState)
        assertFalse(tool.executionStarted)
        assertTrue(tool.canResumeExecution)
    }

    @Test
    fun `replaying saved user message keeps response and tool identities stable`() {
        val message = directRequest("a photo")
        val first = prepareDirectImageToolMessage(listOf(message), Model())!!
        val second = prepareDirectImageToolMessage(listOf(message), Model())!!
        assertEquals(first.id, second.id)
        assertEquals(first.getTools().single().toolCallId, second.getTools().single().toolCallId)
    }

    @Test
    fun `resuming after the tool was created cannot create a second tool`() {
        val user = directRequest("a photo")
        val assistant = prepareDirectImageToolMessage(listOf(user), Model())!!
        assertNull(prepareDirectImageToolMessage(listOf(user, assistant), Model()))
        assertNull(prepareDirectImageToolMessage(listOf(user, assistant, UIMessage.user("继续聊聊文案")), Model()))
    }

    @Test
    fun `empty prompt and unsupported attachments fail before the tool is scheduled`() {
        assertTrue(runCatching { prepareDirectImageToolMessage(listOf(directRequest("  ")), Model()) }.isFailure)
        val message = directRequest("做商品图").copy(parts = listOf(
            UIMessagePart.Text("做商品图"),
            UIMessagePart.Document(url = "file:/brief.pdf", fileName = "brief.pdf", mime = "application/pdf"),
        ))
        assertTrue(runCatching { prepareDirectImageToolMessage(listOf(message), Model()) }.isFailure)
    }

    @Test
    fun `explicit choice is durable in serialized user message`() {
        val message = directRequest("做一张主图")
        val saved = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(UIMessage.serializer(), message))
        assertTrue(saved.annotations.contains(UIMessageAnnotation.DirectImageRequest))
        assertEquals(
            prepareDirectImageToolMessage(listOf(message), Model())!!.getTools().single().toolCallId,
            prepareDirectImageToolMessage(listOf(saved), Model())!!.getTools().single().toolCallId,
        )
    }

    private fun directRequest(prompt: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(prompt)),
        annotations = listOf(UIMessageAnnotation.DirectImageRequest),
    )
}

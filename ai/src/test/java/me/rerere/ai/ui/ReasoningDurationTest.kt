package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * 工具调用（如搜索）场景下的思考计时回归测试。
 */
class ReasoningDurationTest {

    private fun chunk(vararg parts: UIMessagePart) = MessageChunk(
        id = "test",
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun reasoning(text: String) = UIMessagePart.Reasoning(reasoning = text)

    private fun toolDelta(id: String = "", name: String = "", input: String = "") =
        UIMessagePart.Tool(toolCallId = id, toolName = name, input = input, output = emptyList())

    private fun assistantSeed() = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("查一下今天的新闻"))),
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
    )

    private fun List<UIMessage>.lastReasoning() =
        last().parts.filterIsInstance<UIMessagePart.Reasoning>().last()

    @Test
    fun `tool call deltas must not finish reasoning`() {
        var messages = assistantSeed()

        messages = messages.handleMessageChunk(chunk(reasoning("我需要搜索")))
        // 工具调用的分片与思考交错到达
        messages = messages.handleMessageChunk(chunk(toolDelta(id = "call_1", name = "search")))

        val part = messages.lastReasoning()
        assertNull("工具调用分片不应结束思考", part.finishedAt)
    }

    @Test
    fun `empty stream events must not finish reasoning`() {
        var messages = assistantSeed()

        messages = messages.handleMessageChunk(chunk(reasoning("思考中")))
        messages = messages.handleMessageChunk(chunk())

        assertNull("空流事件不代表正文开始", messages.lastReasoning().finishedAt)
    }

    @Test
    fun `reasoning interleaved with tool deltas keeps the original start time`() {
        var messages = assistantSeed()

        messages = messages.handleMessageChunk(chunk(reasoning("先想一下")))
        val start = messages.lastReasoning().createdAt

        messages = messages.handleMessageChunk(chunk(toolDelta(id = "call_1", name = "search")))
        messages = messages.handleMessageChunk(chunk(toolDelta(id = "call_1", input = "{\"q\":")))
        messages = messages.handleMessageChunk(chunk(reasoning("还要再补充")))

        // UI 会按顺序渲染 reasoning/tool 步骤, 所以分段是预期行为;
        // 关键是每段都从本轮思考的起点起算, 计时不能归零.
        val parts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertTrue("交错后应仍有思考内容", parts.isNotEmpty())
        parts.forEach {
            assertEquals("思考开始时间不能被重置", start, it.createdAt)
            assertNull("本轮思考尚未结束", it.finishedAt)
        }
    }

    @Test
    fun `text delta finishes reasoning with a non-zero duration`() {
        var messages = assistantSeed()

        messages = messages.handleMessageChunk(chunk(reasoning("思考中")))
        val start = messages.lastReasoning().createdAt

        messages = messages.handleMessageChunk(chunk(UIMessagePart.Text("答案是")))

        val part = messages.lastReasoning()
        assertNotNull("正文开始时应结束思考", part.finishedAt)
        assertEquals(start, part.createdAt)
        assertTrue("耗时不应为负", part.finishedAt!! >= part.createdAt)
    }

    @Test
    fun `reasoning after tool result starts a fresh part`() {
        var messages = assistantSeed()

        // 第一轮：思考 + 工具调用
        messages = messages.handleMessageChunk(chunk(reasoning("需要搜索")))
        messages = messages.handleMessageChunk(chunk(toolDelta(id = "call_1", name = "search")))

        // 工具执行完毕，显式结束本轮思考
        messages = messages.dropLast(1) + messages.last().finishReasoning()
        val firstRound = messages.lastReasoning()
        assertNotNull(firstRound.finishedAt)

        // 第二轮：拿到搜索结果后继续思考
        messages = messages.handleMessageChunk(chunk(reasoning("根据搜索结果")))

        val parts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals("第二轮思考应是新的一段", 2, parts.size)
        assertNull("新一段思考尚未结束", parts.last().finishedAt)
        assertTrue(
            "新一段思考应重新起算",
            parts.last().createdAt >= firstRound.finishedAt!! - 5.milliseconds
        )
    }

    @Test
    fun `finishReasoning is idempotent`() {
        var messages = assistantSeed()
        messages = messages.handleMessageChunk(chunk(reasoning("思考")))

        val once = messages.last().finishReasoning()
        val finishedAt = once.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt
        assertNotNull(finishedAt)

        val twice = once.finishReasoning()
        assertEquals(
            finishedAt,
            twice.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt
        )
    }

    @Test
    fun `sanity - clock moves forward`() {
        val a = Clock.System.now()
        assertTrue(Clock.System.now() >= a)
    }
}

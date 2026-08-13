package me.rerere.ai.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeProviderStreamEventTest {

    @Test
    fun `done marker completes stream`() {
        assertSame(ClaudeStreamEvent.Completed, decodeClaudeStreamEvent(null, "[DONE]"))
    }

    @Test
    fun `json-only message stop completes stream`() {
        val event = decodeClaudeStreamEvent(null, """{"type":"message_stop"}""")

        assertSame(ClaudeStreamEvent.Completed, event)
    }

    @Test
    fun `event header message stop completes stream without a json type`() {
        val event = decodeClaudeStreamEvent("message_stop", "{}")

        assertSame(ClaudeStreamEvent.Completed, event)
    }

    @Test
    fun `json-only error fails stream with upstream message`() {
        val event = decodeClaudeStreamEvent(
            sseType = null,
            data = """{"type":"error","error":{"type":"overloaded_error","message":"Upstream overloaded"}}""",
        )

        assertTrue(event is ClaudeStreamEvent.Failed)
        assertEquals("Upstream overloaded", (event as ClaudeStreamEvent.Failed).cause.message)
    }
}

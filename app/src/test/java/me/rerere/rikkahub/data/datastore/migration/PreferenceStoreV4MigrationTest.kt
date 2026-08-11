package me.rerere.rikkahub.data.datastore.migration

import me.rerere.rikkahub.data.datastore.BINGO_PROVIDER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreV4MigrationTest {

    @Test
    fun `drops providers left over from a pre-lockdown install`() {
        val raw = """
            [
              {"id":"$BINGO_PROVIDER_ID","name":"Bingo"},
              {"id":"1eb2a1f6-0000-4000-8000-000000000001","name":"OpenAI","apiKey":"sk-user-key"},
              {"id":"1eb2a1f6-0000-4000-8000-000000000002","name":"OpenRouter"}
            ]
        """.trimIndent()

        val result = pruneProviderJson(raw)

        assertTrue(result is PruneResult.Pruned)
        val pruned = result as PruneResult.Pruned
        assertEquals(2, pruned.dropped)
        assertTrue(pruned.json.contains(BINGO_PROVIDER_ID.toString()))
        // The user's own key must not survive into the locked-down build.
        assertFalse(pruned.json.contains("sk-user-key"))
        assertFalse(pruned.json.contains("OpenRouter"))
    }

    @Test
    fun `leaves a clean install untouched`() {
        val raw = """[{"id":"$BINGO_PROVIDER_ID","name":"Bingo"}]"""

        assertEquals(PruneResult.Unchanged, pruneProviderJson(raw))
    }

    @Test
    fun `reports malformed provider json instead of throwing`() {
        // A decode failure that propagated would re-run on every launch and brick the app.
        assertEquals(PruneResult.Unparseable, pruneProviderJson("{not json"))
        assertEquals(PruneResult.Unparseable, pruneProviderJson("""{"id":"x"}"""))
    }

    @Test
    fun `drops every provider when none is the bingo container`() {
        val raw = """[{"id":"1eb2a1f6-0000-4000-8000-000000000001","name":"OpenAI"}]"""

        val result = pruneProviderJson(raw)

        assertEquals(1, (result as PruneResult.Pruned).dropped)
        assertEquals("[]", result.json)
    }

    @Test
    fun `strips assistant model pins while preserving other fields`() {
        val raw = """
            [
              {"id":"a","name":"Kept","chatModelId":"1eb2a1f6-0000-4000-8000-00000000dead"},
              {"id":"b","name":"NoPin"}
            ]
        """.trimIndent()

        val cleaned = stripAssistantModelPins(raw)

        assertFalse(cleaned!!.contains("chatModelId"))
        assertTrue(cleaned.contains("Kept"))
        assertTrue(cleaned.contains("NoPin"))
    }

    @Test
    fun `returns null for malformed assistant json`() {
        assertNull(stripAssistantModelPins("{not json"))
    }
}

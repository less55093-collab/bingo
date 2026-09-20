package me.rerere.rikkahub.data.auth

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingImageTaskStoreTest {
    @Test
    fun `accounts stay isolated across store recreation and identical request IDs`() = withStore { root, store ->
        store.save(1, PendingImageTask(requestId = "request", prompt = "account one"))
        store.save(2, PendingImageTask(requestId = "request", prompt = "account two"))

        val reopened = PendingImageTaskStore(root)
        assertEquals("account one", reopened.read(1).tasks.single().prompt)
        assertEquals("account two", reopened.read(2).tasks.single().prompt)
        assertTrue(reopened.read(3).tasks.isEmpty())
        assertTrue(runCatching { store.save(2, reopened.read(1).tasks.single()) }.isFailure)
    }

    @Test
    fun `damaged record survives writes while healthy tasks remain recoverable`() = withStore { root, store ->
        store.save(1, PendingImageTask(requestId = "broken"))
        val damaged = root.walkTopDown().single { it.extension == "json" }.apply { writeText("{incomplete") }
        store.save(1, PendingImageTask(requestId = "healthy"))

        val result = PendingImageTaskStore(root).read(1)
        assertEquals(listOf("healthy"), result.tasks.map { it.requestId })
        assertEquals(1, result.damagedRecords)
        assertEquals("{incomplete", damaged.readText())
        store.remove(1) { it.requestId == "healthy" }
        assertTrue(damaged.exists())
    }

    @Test
    fun `corrupt primary falls back to complete prior record without losing identity`() = withStore { root, store ->
        store.save(1, PendingImageTask(requestId = "stable", taskId = "accepted"))
        store.update(1, "stable") { it.copy(completedPaths = listOf("/image.png")) }
        root.walkTopDown().single { it.extension == "json" }.writeText("truncated")

        val result = PendingImageTaskStore(root).read(1)
        assertEquals("stable", result.tasks.single().requestId)
        assertEquals("accepted", result.tasks.single().taskId)
        assertEquals(1, result.damagedRecords)
    }

    @Test
    fun `mixed legacy import preserves invalid entry and never overwrites newer state`() = withStore { root, store ->
        val legacy = """[{"requestId":"good","taskId":"accepted"},{"numOfImages":"broken"}]"""
        store.importLegacy(1, legacy)
        store.update(1, "good") { it.copy(completedPaths = listOf("/result.png"), deliveryError = "chat pending") }
        store.importLegacy(1, legacy)

        val result = PendingImageTaskStore(root).read(1)
        assertEquals(listOf("/result.png"), result.tasks.single().completedPaths)
        assertEquals("chat pending", result.tasks.single().deliveryError)
        assertEquals(1, result.damagedRecords)
        assertTrue(root.walkTopDown().any { it.name.endsWith(".corrupt") })
    }

    @Test
    fun `ownerless legacy record is not inherited by a newly signed in account`() = withStore { root, store ->
        store.preserveUnownedLegacy("""[{"requestId":"private"}]""")
        store.save(42, PendingImageTask(requestId = "new-account"))

        assertEquals(listOf("new-account"), store.read(42).tasks.map { it.requestId })
        assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("unowned-") })
    }

    @Test
    fun `task associations and completed delivery data survive a restart`() = withStore { root, store ->
        val task = PendingImageTask(
            requestId = "request", conversationId = "conversation", messageId = "message", toolCallId = "tool",
            operationId = "operation", itemId = "item", completedPaths = listOf("/one.png", "/two.png"),
        )
        store.save(1, task)
        val decoded = PendingImageTaskStore(root).read(1).tasks.single()
        assertEquals(task.copy(accountId = 1), decoded)
        assertEquals(decoded, JsonInstant.decodeFromString<PendingImageTask>(JsonInstant.encodeToString(decoded)))
    }

    @Test
    fun `updating missing task cannot recreate an already removed record`() = withStore { _, store ->
        store.save(1, PendingImageTask(requestId = "done"))
        store.remove(1) { it.requestId == "done" }
        store.update(1, "done") { it.copy(taskId = "late callback") }
        assertTrue(store.read(1).tasks.isEmpty())
    }

    @Test
    fun `failed record install leaves earlier committed task intact`() = withStore { root, store ->
        store.save(1, PendingImageTask(requestId = "stable", taskId = "accepted"))
        val target = root.walkTopDown().single { it.extension == "json" }
        // A filesystem conflict during the backup write simulates a failed disk operation.
        File(target.parentFile, target.name + ".bak").mkdir()
        assertTrue(runCatching { store.update(1, "stable") { it.copy(taskId = "new") } }.isFailure)
        assertEquals("accepted", store.read(1).tasks.single().taskId)
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    private fun withStore(block: (File, PendingImageTaskStore) -> Unit) {
        val root = Files.createTempDirectory("pending-image-task").toFile()
        try {
            block(root, PendingImageTaskStore(root))
        } finally {
            root.deleteRecursively()
        }
    }
}

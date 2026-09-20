package me.rerere.rikkahub.data.auth

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.rikkahub.utils.JsonInstant

/** One atomic record per account/request. A damaged record cannot overwrite its neighbours. */
internal class PendingImageTaskStore(private val root: File) {
    val revision = MutableStateFlow(0L)

    data class ReadResult(val tasks: List<PendingImageTask>, val damagedRecords: Int)

    @Synchronized
    fun read(accountId: Long): ReadResult {
        val directory = accountDirectory(accountId)
        if (!directory.exists()) return ReadResult(emptyList(), 0)
        val files = directory.listFiles() ?: throw IOException("无法读取图片任务记录")
        var damaged = files.count { it.name.endsWith(".corrupt") }
        val tasks = files.filter { it.name.endsWith(".json") }.mapNotNull { file ->
            try {
                decode(file.readText(), accountId)
            } catch (error: Exception) {
                damaged++
                // Keep the damaged bytes for diagnosis and try the last complete version. Never
                // turn a broken record into an empty list that a later write could replace.
                val backup = File(directory, file.name + ".bak")
                if (!backup.isFile) null else runCatching { decode(backup.readText(), accountId) }.getOrNull()
            }
        }
        return ReadResult(tasks.sortedBy { it.createdAt }, damaged)
    }

    @Synchronized
    fun save(accountId: Long, task: PendingImageTask) {
        require(task.accountId == null || task.accountId == accountId) { "图片任务属于其他账号" }
        val normalized = task.normalized().copy(accountId = accountId)
        val target = taskFile(accountId, normalized.requestId)
        val encoded = JsonInstant.encodeToString(normalized)
        // Only a successfully decoded primary may replace the backup. Corruption must never
        // overwrite the last readable copy.
        if (target.isFile && runCatching { decode(target.readText(), accountId) }.isSuccess) {
            atomicWrite(File(target.parentFile, target.name + ".bak"), target.readBytes())
        }
        atomicWrite(target, encoded.toByteArray(Charsets.UTF_8))
        revision.value++
    }

    @Synchronized
    fun update(accountId: Long, requestId: String, transform: (PendingImageTask) -> PendingImageTask) {
        val task = read(accountId).tasks.firstOrNull { it.requestId == requestId } ?: return
        save(accountId, transform(task))
    }

    @Synchronized
    fun remove(accountId: Long, predicate: (PendingImageTask) -> Boolean) {
        read(accountId).tasks.filter(predicate).forEach { task ->
            val file = taskFile(accountId, task.requestId)
            val backup = File(file.parentFile, file.name + ".bak")
            // Remove the backup first so a crash cannot resurrect a completed task.
            if (backup.exists() && !backup.delete()) throw IOException("无法更新图片任务记录")
            if (file.exists() && !file.delete()) throw IOException("无法更新图片任务记录")
            syncDirectory(file.parentFile)
        }
        revision.value++
    }

    /** Import is replayable if the process dies before the old preference is removed. */
    @Synchronized
    fun importLegacy(accountId: Long, encoded: String) {
        val directory = accountDirectory(accountId)
        val elements = try {
            JsonInstant.parseToJsonElement(encoded) as? JsonArray ?: error("Expected task array")
        } catch (_: Exception) {
            atomicWrite(File(directory, "legacy-${digest(encoded)}.corrupt"), encoded.toByteArray())
            revision.value++
            return
        }
        elements.forEach { element ->
            val raw = element.toString()
            val task = runCatching {
                JsonInstant.decodeFromJsonElement<PendingImageTask>(element).let { task ->
                    task.copy(
                        accountId = task.accountId ?: accountId,
                        requestId = task.requestId.ifBlank {
                            task.taskId?.takeIf(String::isNotBlank)
                                ?: UUID.nameUUIDFromBytes(raw.toByteArray()).toString()
                        },
                    )
                }
            }.getOrNull()
            if (task == null || task.accountId != accountId) {
                atomicWrite(File(directory, "legacy-${digest(raw)}.corrupt"), raw.toByteArray())
            } else if (!taskFile(accountId, task.requestId).exists()) {
                save(accountId, task)
            }
        }
        revision.value++
    }

    /** Ownerless legacy records remain quarantined; a later account must never adopt them. */
    @Synchronized
    fun preserveUnownedLegacy(encoded: String) {
        atomicWrite(File(root, "unowned-${digest(encoded)}.corrupt"), encoded.toByteArray())
    }

    private fun decode(encoded: String, accountId: Long): PendingImageTask {
        val task = JsonInstant.decodeFromString<PendingImageTask>(encoded)
        require(task.accountId == accountId && task.requestId.isNotBlank()) { "Invalid task owner or identity" }
        return task
    }

    private fun accountDirectory(accountId: Long): File {
        require(accountId > 0) { "图片任务缺少有效账号" }
        return File(root, accountId.toString())
    }

    private fun taskFile(accountId: Long, requestId: String): File =
        File(accountDirectory(accountId), digest(requestId) + ".json")

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val directory = target.parentFile ?: throw IOException("图片任务目录无效")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建图片任务目录")
        val temporary = Files.createTempFile(directory.toPath(), ".task-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: UnsupportedOperationException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirectory(directory)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun syncDirectory(directory: File) {
        // Some Android filesystems do not expose directory fsync; record contents are already synced.
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

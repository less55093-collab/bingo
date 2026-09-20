package me.rerere.rikkahub.service

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Acknowledgements survive gallery deletion, so account reconciliation cannot resurrect it. */
internal class ImageDeliveryReceiptStore(private val root: File) {
    private fun path(accountId: Long, requestId: String): File {
        require(accountId > 0)
        val name = UUID.nameUUIDFromBytes(requestId.toByteArray(Charsets.UTF_8)).toString()
        return File(root, "$accountId/$name.receipt")
    }

    fun contains(accountId: Long, requestId: String): Boolean =
        runCatching { path(accountId, requestId).readText() == requestId }.getOrDefault(false)

    fun record(accountId: Long, requestId: String) {
        val target = path(accountId, requestId)
        val parent = checkNotNull(target.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法保存图片交付记录")
        val temporary = File.createTempFile("receipt-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(requestId.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching {
                java.nio.channels.FileChannel.open(parent.toPath(), java.nio.file.StandardOpenOption.READ)
                    .use { it.force(true) }
            }
        } finally {
            temporary.delete()
        }
    }
}

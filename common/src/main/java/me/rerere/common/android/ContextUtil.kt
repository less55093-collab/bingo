package me.rerere.common.android

import android.content.Context
import java.io.File

val Context.appTempFolder: File
    get() {
        val dir = File(cacheDir, "temp")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

fun Context.getCacheDirectory(namespace: String): File {
    val dir = File(cacheDir, "disk_cache/$namespace")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

/**
 * Input images copied for an asynchronous image-edit request. This lives under
 * filesDir because cacheDir may be evicted while the request is still pending.
 */
val Context.imageGenerationInputFolder: File
    get() {
        val dir = File(filesDir, "image_generation_inputs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

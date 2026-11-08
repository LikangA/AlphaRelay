package com.alpharelay.camera

import android.content.Context
import java.io.File

/**
 * 大图预览缓存：只存从 RAW 里抠出来的那张 JPEG，不存原始 ARW。
 */
object PreviewCache {

    private fun dir(context: Context): File = File(context.cacheDir, "previews").apply { mkdirs() }

    private fun fileFor(context: Context, f: CameraFile): File =
        File(dir(context), Thumbs.hashName(Thumbs.keyOf(f)) + ".jpg")

    fun read(context: Context, f: CameraFile): ByteArray? {
        val file = fileFor(context, f)
        if (!file.exists() || file.length() < 1024) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun write(context: Context, f: CameraFile, bytes: ByteArray) {
        runCatching { fileFor(context, f).writeBytes(bytes) }
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }
}

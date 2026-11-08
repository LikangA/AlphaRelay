package com.alpharelay.camera

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 大图预览用的缓存：RAW 先整份下载，抠出内嵌 JPEG 后只把 JPEG 留在磁盘上，
 * 下次再看同一张不用再走 USB。
 */
object PreviewCache {

    private fun dir(context: Context): File = File(context.cacheDir, "previews").apply { mkdirs() }

    private fun fileFor(context: Context, f: CameraFile): File =
        File(dir(context), "${f.handle}_${f.name}.jpg")

    suspend fun load(context: Context, f: CameraFile): ByteArray? = withContext(Dispatchers.IO) {
        val cached = fileFor(context, f)
        if (cached.exists() && cached.length() > 1024) return@withContext cached.readBytes()

        val temp = File(context.cacheDir, "preview_${f.handle}.bin")
        if (!CameraHub.download(f, temp)) {
            runCatching { temp.delete() }
            return@withContext null
        }

        val bytes = try {
            val raw = temp.readBytes()
            if (f.kind == FileKind.RAW) PreviewExtractor.extract(raw) ?: raw else raw
        } catch (e: Exception) {
            null
        }
        runCatching { temp.delete() }

        if (bytes == null) return@withContext null
        runCatching { cached.writeBytes(bytes) }
        bytes
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }
}

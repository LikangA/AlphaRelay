package com.alpharelay.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 缩略图两层缓存：
 * 内存 LRU（滚动时不卡）+ 磁盘 cacheDir/thumbs（重开 App、重新插线都还在）。
 *
 * 缓存 key 用「文件名+大小+修改时间」而不是 MTP handle ——
 * handle 每次重连都会变，用 handle 当 key 等于每次都重新读卡。
 */
object Thumbs {

    private val memory = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun keyOf(f: CameraFile): String = "${f.name}|${f.size}|${f.modifiedSec}"

    fun cached(key: String): Bitmap? = memory.get(key)

    fun remember(key: String, bitmap: Bitmap) {
        memory.put(key, bitmap)
    }

    fun clearMemory() {
        memory.evictAll()
    }

    fun hashName(key: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val builder = StringBuilder(digest.size * 2)
        for (b in digest) {
            builder.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return builder.toString()
    }

    private fun diskFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        return File(dir, hashName(key) + ".jpg")
    }

    suspend fun diskBytes(context: Context, key: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = diskFile(context, key)
        if (!file.exists() || file.length() < 1024) return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }

    suspend fun saveDisk(context: Context, key: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            runCatching { diskFile(context, key).writeBytes(bytes) }
        }
    }

    fun decode(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sample = 1
        while (width / (sample * 2) >= maxDimension && height / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

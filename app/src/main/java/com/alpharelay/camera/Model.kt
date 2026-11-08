package com.alpharelay.camera

import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FileKind(val label: String) {
    RAW("RAW"),
    JPEG("JPEG"),
    HEIF("HEIF"),
    VIDEO("视频"),
    OTHER("其他")
}

/**
 * 相机存储卡上的一个文件。只要 handle 不变，就可以拿它去 MTP 会话里取数据。
 */
data class CameraFile(
    val handle: Int,
    val name: String,
    val size: Long,
    val modifiedSec: Long,
    val width: Int,
    val height: Int,
    val kind: FileKind
) : Serializable

object Formats {

    fun kindOf(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "arw", "srf", "sr2", "dng", "cr2", "cr3", "nef", "raf", "orf", "rw2", "pef" -> FileKind.RAW
        "jpg", "jpeg", "jpe", "png", "tif", "tiff", "bmp", "webp" -> FileKind.JPEG
        "heic", "heif", "hif", "avif" -> FileKind.HEIF
        "mp4", "mov", "mts", "m2ts", "avi", "mkv", "mpg", "mpeg", "xavc" -> FileKind.VIDEO
        else -> FileKind.OTHER
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "arw" -> "image/x-sony-arw"
        "dng" -> "image/x-adobe-dng"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "png" -> "image/png"
        "tif", "tiff" -> "image/tiff"
        "webp" -> "image/webp"
        "heic", "heif", "hif" -> "image/heif"
        "avif" -> "image/avif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mts", "m2ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    fun size(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun date(sec: Long): String {
        if (sec <= 0L) return "—"
        return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(sec * 1000L))
    }

    /** 相册子目录名，按拍摄日期分桶。 */
    fun day(sec: Long): String {
        val ms = if (sec > 0L) sec * 1000L else System.currentTimeMillis()
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    }
}

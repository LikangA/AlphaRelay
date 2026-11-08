package com.alpharelay.camera

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class PreviewResult {
    class Ok(val bytes: ByteArray) : PreviewResult()
    class Fail(val message: String) : PreviewResult()
}

/**
 * 大图预览的数据源，按「磁盘缓存 → 相机」的顺序取。
 * 拿不到时返回带原因的 Fail，界面直接把原因显示出来，不再是一句「失败」。
 */
object PreviewRepository {

    suspend fun load(context: Context, f: CameraFile): PreviewResult = withContext(Dispatchers.IO) {
        PreviewCache.read(context, f)?.let { return@withContext PreviewResult.Ok(it) }

        val temp = File(context.cacheDir, "preview_${System.currentTimeMillis()}.bin")
        try {
            if (!CameraHub.download(f, temp)) {
                return@withContext PreviewResult.Fail("从相机读取失败：文件为空或传输中断")
            }
            val raw = temp.readBytes()
            val bytes = if (f.kind == FileKind.RAW) {
                val extracted = PreviewExtractor.extract(raw)
                if (extracted == null) {
                    return@withContext PreviewResult.Fail("这张 RAW 里没找到内嵌预览图")
                }
                extracted
            } else {
                raw
            }
            PreviewCache.write(context, f, bytes)
            PreviewResult.Ok(bytes)
        } catch (e: Exception) {
            Diag.error("preview", "读取 ${f.name} 失败", e)
            PreviewResult.Fail("读取失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            runCatching { temp.delete() }
        }
    }
}

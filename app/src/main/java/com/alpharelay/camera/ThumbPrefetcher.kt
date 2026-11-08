package com.alpharelay.camera

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台补缩略图。
 *
 * 优先级：还没有缩略图的排前面（也就是上次缓存之后新拍的照片），
 * 同一优先级里按拍摄时间从新到旧。已经在磁盘缓存里的一律跳过。
 * 导出进行时自动让路，导出结束再继续。
 */
object ThumbPrefetcher {

    private var job: Job? = null
    private val failed = HashSet<String>()

    /** 只有最新的这么多张允许整份读取兜底。 */
    private const val FULL_DOWNLOAD_LIMIT = 40

    fun ensure(
        scope: CoroutineScope,
        context: Context,
        files: List<CameraFile>,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit
    ) {
        if (job?.isActive == true) return
        start(scope, context, files, onProgress, onDone)
    }

    fun restart(
        scope: CoroutineScope,
        context: Context,
        files: List<CameraFile>,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit
    ) {
        job?.cancel()
        start(scope, context, files, onProgress, onDone)
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun start(
        scope: CoroutineScope,
        context: Context,
        files: List<CameraFile>,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit
    ) {
        val app = context.applicationContext
        job = scope.launch(Dispatchers.Default) {
            val pending = ArrayList<CameraFile>()
            for (f in files) {
                if (f.kind == FileKind.VIDEO) continue
                val key = Thumbs.keyOf(f)
                if (Thumbs.cached(key) != null) continue
                if (failed.contains(key)) continue
                if (Thumbs.hasDisk(app, key)) continue
                pending.add(f)
            }
            pending.sortWith(compareByDescending<CameraFile> { it.modifiedSec })
            if (pending.isEmpty()) {
                onDone()
                return@launch
            }

            var index = 0
            for (f in pending) {
                if (!isActive) break
                // 导出优先，别抢 USB 带宽
                while (ExportBus.state.value.running) delay(500)

                val key = Thumbs.keyOf(f)
                // 最新的一批允许「整份读取」兜底；更老的只走便宜的路径，避免一次插线搬几十 GB
                val allowFull = index < FULL_DOWNLOAD_LIMIT
                val bytes = try {
                    CameraHub.thumbnailBytes(f, allowFull)
                } catch (e: Exception) {
                    null
                }
                if (bytes != null) {
                    Thumbs.saveDisk(app, key, bytes)
                } else {
                    // 这张没有内嵌预览，本次会话不再重试
                    failed.add(key)
                }
                index++
                onProgress(index, pending.size)
                // 让出一点时间，用户正在看的那张能插队
                delay(15)
            }
            onDone()
        }
    }
}

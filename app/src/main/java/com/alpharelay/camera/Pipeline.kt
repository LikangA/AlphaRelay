package com.alpharelay.camera

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 导出进度总线：Service 写，界面读。 */
object ExportBus {

    data class State(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val ok: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val current: String = "",
        val summary: String = ""
    )

    val state = MutableStateFlow(State())

    private var cancelRequested = false

    val cancelled: Boolean get() = cancelRequested

    fun begin(total: Int) {
        cancelRequested = false
        state.value = State(running = true, total = total)
    }

    fun tick(done: Int, ok: Int, failed: Int, current: String) {
        state.value = state.value.copy(done = done, ok = ok, failed = failed, current = current)
    }

    fun end(total: Int, ok: Int, failed: Int, skipped: Int = 0, cancelled: Boolean = false) {
        val summary = if (cancelled) {
            val extra = if (failed > 0) "，失败 $failed 个" else ""
            "已取消：已导出 $ok 个$extra"
        } else {
            val parts = ArrayList<String>(4)
            if (ok > 0) parts.add("已导出 $ok 个")
            if (skipped > 0) parts.add("跳过 $skipped 个（之前导过）")
            if (failed > 0) parts.add("失败 $failed 个")
            if (parts.isEmpty()) parts.add("没有需要导出的文件")
            parts.joinToString("，")
        }
        state.value = state.value.copy(
            running = false,
            done = total,
            ok = ok,
            failed = failed,
            skipped = skipped,
            current = "",
            summary = summary
        )
    }

    fun cancel() {
        cancelRequested = true
    }

    fun clear() {
        state.value = State()
    }
}

/** 把临时文件放进系统相册（MediaStore），Android 9 及以下走公共目录 + 媒体扫描。 */
object Publisher {

    fun publish(context: Context, source: File, f: CameraFile): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishModern(context, source, f)
            } else {
                publishLegacy(context, source, f)
            }
        } catch (e: Exception) {
            Diag.error("publish", "写入相册失败 ${f.name}", e)
            false
        }
    }

    /** 按拍摄日期分子目录，方便和相机里的目录结构对上。 */
    private fun relativeFolder(f: CameraFile): String {
        val sec = if (f.modifiedSec > 0L) f.modifiedSec else System.currentTimeMillis() / 1000L
        return "AlphaRelay/" + Formats.day(sec)
    }

    private fun publishModern(context: Context, source: File, f: CameraFile): Boolean {
        val resolver = context.contentResolver
        val video = f.kind == FileKind.VIDEO
        val collection: Uri = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val base = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, Formats.mimeOf(f.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$base/${relativeFolder(f)}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (f.modifiedSec > 0L) put(MediaStore.MediaColumns.DATE_MODIFIED, f.modifiedSec)
        }
        val uri = resolver.insert(collection, values) ?: return false

        var written = false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
                out.flush()
                written = true
            }
        } catch (e: Exception) {
            Diag.error("publish", "写入失败 ${f.name}", e)
            written = false
        }
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { resolver.update(uri, done, null, null) }
        return true
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(context: Context, source: File, f: CameraFile): Boolean {
        val base = if (f.kind == FileKind.VIDEO) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }
        val dir = File(base, relativeFolder(f))
        if (!dir.exists() && !dir.mkdirs()) return false

        var dest = File(dir, f.name)
        if (dest.exists()) {
            val stem = f.name.substringBeforeLast('.', f.name)
            val ext = f.name.substringAfterLast('.', "")
            var n = 1
            while (dest.exists() && n < 500) {
                dest = File(dir, if (ext.isEmpty()) "${stem}_$n" else "${stem}_$n.$ext")
                n++
            }
        }
        source.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        return true
    }
}

/**
 * 前台服务里跑批量导出。放前台是因为 32GB 卡走 MTP 可能要几十分钟，
 * 界面切到后台不能让系统把进程回收掉。
 *
 * 任务进队列串行执行：即拍即传触发的小批次不会把正在跑的整卡导出打断。
 */
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<List<CameraFile>>()
    private var draining = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_CANCEL -> {
                ExportBus.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val files = intent.getSerializableExtra(EXTRA_FILES) as? ArrayList<CameraFile> ?: arrayListOf()
                if (files.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val queued = synchronized(queue) {
                    queue.addLast(files)
                    queue.sumOf { it.size }
                }
                promoteToForeground(queued)
                if (!draining) drain()
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(total: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0, total, "准备中…", true), type)
    }

    private fun drain() {
        draining = true
        scope.launch {
            while (true) {
                val batch = synchronized(queue) { queue.removeFirstOrNull() }
                if (batch == null) break
                process(batch)
            }
            draining = false
            val again = synchronized(queue) { queue.isNotEmpty() }
            if (again) {
                drain()
            } else {
                stopSelf()
            }
        }
    }

    private suspend fun process(files: List<CameraFile>) {
        ExportBus.begin(files.size)
        var ok = 0
        var failed = 0
        var skipped = 0

        files.forEachIndexed { index, f ->
            if (ExportBus.cancelled) return@forEachIndexed

            val key = ExportIndex.keyOf(f)
            if (ExportIndex.contains(this, key)) {
                skipped++
                return@forEachIndexed
            }

            ExportBus.tick(index, ok, failed, f.name)
            postProgress(index, files.size, f.name, true)

            val temp = File(cacheDir, "import_${f.handle}.bin")
            val downloaded = CameraHub.download(f, temp)
            var saved = false
            if (downloaded) {
                saved = Publisher.publish(this, temp, f)
            }
            runCatching { temp.delete() }

            if (saved) {
                ok++
                ExportIndex.add(this, key)
            } else {
                failed++
                Diag.error("export", "导出失败 ${f.name}")
            }
            ExportBus.tick(index + 1, ok, failed, f.name)
            postProgress(index + 1, files.size, f.name, true)
        }

        ExportBus.end(ok + failed + skipped, ok, failed, skipped, ExportBus.cancelled)
        postDone()
        Diag.info("export", "批次结束 ok=$ok failed=$failed skipped=$skipped")
    }

    private fun postProgress(done: Int, total: Int, name: String, ongoing: Boolean) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        runCatching { mgr.notify(NOTIFICATION_ID, buildNotification(done, total, name, ongoing)) }
    }

    private fun postDone() {
        val summary = ExportBus.state.value.summary.ifBlank { "导出结束" }
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val builder = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (ExportBus.cancelled) "已取消" else "导出结束")
            .setContentText(summary)
            .setAutoCancel(true)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        runCatching { mgr.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun buildNotification(done: Int, total: Int, name: String, ongoing: Boolean): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = if (launch != null) {
            PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }
        val cancelIntent = Intent(this, ImportService::class.java).setAction(ACTION_CANCEL)
        val cancelPending = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val max = total.coerceAtLeast(1)
        val builder = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (total > 0) "正在导出 $done/$total" else "正在导出")
            .setContentText(name)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(max, done.coerceIn(0, max), false)
        if (ongoing) builder.addAction(0, "取消", cancelPending)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.alpharelay.camera.action.START"
        const val ACTION_CANCEL = "com.alpharelay.camera.action.CANCEL"
        const val EXTRA_FILES = "files"
        private const val NOTIFICATION_ID = 1001
    }
}

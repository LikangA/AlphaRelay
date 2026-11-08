package com.alpharelay.camera

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简诊断日志：内存里留最近 300 条，错误额外落一份到 files/diag.log。
 * App 是离线自用工具，出问题时用户能把这段日志直接复制出来，比"导出失败"四个字有用得多。
 */
object Diag {

    private const val MAX_LINES = 600
    private const val FILE_MAX_BYTES = 256 * 1024

    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private var app: Context? = null

    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun info(tag: String, message: String) {
        Log.d("AlphaRelay/$tag", message)
        add("$tag: $message")
    }

    fun error(tag: String, message: String, e: Throwable? = null) {
        val detail = if (e == null) {
            message
        } else {
            "$message (${e.javaClass.simpleName}: ${e.message})"
        }
        Log.e("AlphaRelay/$tag", detail, e)
        add("$tag: $detail")
        persist(detail)
    }

    private fun add(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lock) {
            lines.addLast("$stamp $line")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    private fun persist(line: String) {
        val ctx = app ?: return
        runCatching {
            val file = File(ctx.filesDir, "diag.log")
            if (file.exists() && file.length() > FILE_MAX_BYTES) file.delete()
            file.appendText(line + "\n")
        }
    }

    fun dump(): String {
        val builder = StringBuilder()
        val ctx = app
        builder.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android API ").append(Build.VERSION.SDK_INT).append('\n')
        if (ctx != null) {
            builder.append("导出记录：").append(ExportIndex.size(ctx)).append(" 条\n")
        }
        synchronized(lock) {
            if (lines.isEmpty()) {
                builder.append("(还没有日志，连一次相机试试)\n")
            } else {
                for (line in lines) builder.append(line).append('\n')
            }
        }
        if (ctx != null) {
            val file = File(ctx.filesDir, "diag.log")
            if (file.exists() && file.length() > 0) {
                builder.append("\n--- 历史错误 ---\n")
                runCatching { builder.append(file.readText().takeLast(8000)) }
            }
        }
        return builder.toString()
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        app?.let { ctx -> runCatching { File(ctx.filesDir, "diag.log").delete() } }
    }
}

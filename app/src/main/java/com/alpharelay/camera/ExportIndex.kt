package com.alpharelay.camera

import android.content.Context

/**
 * 已导出文件索引，用「文件名 + 大小 + 修改时间」当指纹。
 *
 * 没有它的话，第二次点「导出全部」会把整张卡再拷一遍，相册里出现一堆 _1 _2 副本。
 * 换了卡、或想重导时，用菜单里的「清空导出记录」。
 */
object ExportIndex {

    private const val PREFS = "alpharelay_export_index"
    private const val KEY = "keys"
    private const val HARD_LIMIT = 60000

    private var cached: MutableSet<String>? = null

    fun keyOf(f: CameraFile): String = "${f.name}|${f.size}|${f.modifiedSec}"

    @Synchronized
    private fun set(context: Context): MutableSet<String> {
        cached?.let { return it }
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null)
        val fresh = if (stored == null) HashSet() else HashSet(stored)
        cached = fresh
        return fresh
    }

    @Synchronized
    fun contains(context: Context, key: String): Boolean = set(context).contains(key)

    @Synchronized
    fun add(context: Context, key: String) {
        val set = set(context)
        if (set.size > HARD_LIMIT) set.clear()
        if (set.add(key)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY, HashSet(set))
                .apply()
        }
    }

    @Synchronized
    fun size(context: Context): Int = set(context).size

    @Synchronized
    fun clear(context: Context) {
        cached = HashSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

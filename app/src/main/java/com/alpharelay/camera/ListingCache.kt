package com.alpharelay.camera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 上一次扫卡的清单，落成 JSON。
 *
 * MTP 的 handle 每次会话都会变，所以缓存里的 handle 只用于「先显示」，
 * 真正导出/预览前必须等后台刷新把 handle 换成新的。
 */
object ListingCache {

    private const val FILE_NAME = "listing.json"

    fun save(context: Context, files: List<CameraFile>) {
        runCatching {
            val array = JSONArray()
            for (f in files) {
                val item = JSONObject()
                item.put("h", f.handle)
                item.put("n", f.name)
                item.put("s", f.size)
                item.put("m", f.modifiedSec)
                item.put("w", f.width)
                item.put("v", f.height)
                array.put(item)
            }
            File(context.filesDir, FILE_NAME).writeText(array.toString())
        }
    }

    fun load(context: Context): List<CameraFile> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            val result = ArrayList<CameraFile>(array.length())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.getString("n")
                result.add(
                    CameraFile(
                        handle = item.getInt("h"),
                        name = name,
                        size = item.getLong("s"),
                        modifiedSec = item.getLong("m"),
                        width = item.optInt("w"),
                        height = item.optInt("v"),
                        kind = Formats.kindOf(name)
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}

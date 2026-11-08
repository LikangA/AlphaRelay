package com.alpharelay.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Thumbs {

    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun clear() {
        cache.evictAll()
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

class FileAdapter(
    private val loader: suspend (CameraFile) -> ByteArray?,
    private val onTap: (CameraFile) -> Unit,
    private val onLongPress: (CameraFile) -> Unit
) : RecyclerView.Adapter<FileAdapter.Holder>() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var items: List<CameraFile> = emptyList()
    private val selected = HashSet<Int>()

    fun submit(list: List<CameraFile>, selection: Set<Int>) {
        items = list
        selected.clear()
        selected.addAll(selection)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item, selected.contains(item.handle))
    }

    override fun onViewRecycled(holder: Holder) {
        holder.cancelLoad()
        super.onViewRecycled(holder)
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

        private val card: MaterialCardView = view.findViewById(R.id.card)
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val name: TextView = view.findViewById(R.id.name)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val badge: TextView = view.findViewById(R.id.badge)
        private val check: ImageView = view.findViewById(R.id.check)
        private var loadJob: Job? = null

        fun bind(item: CameraFile, isSelected: Boolean) {
            name.text = item.name
            val base = "${Formats.size(item.size)} · ${Formats.date(item.modifiedSec)}"
            meta.text = if (ExportIndex.contains(itemView.context, ExportIndex.keyOf(item))) {
                "$base · ${itemView.context.getString(R.string.already_exported)}"
            } else {
                base
            }
            badge.text = item.kind.label
            check.visibility = if (isSelected) View.VISIBLE else View.GONE
            card.strokeWidth = if (isSelected) dp(2) else 0
            card.setOnClickListener { onTap(item) }
            card.setOnLongClickListener {
                onLongPress(item)
                true
            }

            loadJob?.cancel()
            thumb.tag = item.handle
            val key = item.handle.toString()
            val cached = Thumbs.get(key)
            if (cached != null) {
                thumb.setImageBitmap(cached)
                return
            }
            thumb.setImageResource(R.drawable.ph_photo)
            loadJob = scope.launch {
                val bytes = try {
                    loader(item)
                } catch (e: Exception) {
                    null
                } ?: return@launch
                val bitmap = withContext(Dispatchers.Default) { Thumbs.decode(bytes, 480) } ?: return@launch
                Thumbs.put(key, bitmap)
                if (thumb.tag == item.handle) thumb.setImageBitmap(bitmap)
            }
        }

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        private fun dp(value: Int): Int =
            (value * itemView.resources.displayMetrics.density + 0.5f).toInt()
    }
}

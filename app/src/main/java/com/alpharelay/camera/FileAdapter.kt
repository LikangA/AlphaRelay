package com.alpharelay.camera

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

class FileAdapter(
    private val loader: suspend (CameraFile) -> ByteArray?,
    private val onOpen: (CameraFile) -> Unit,
    private val onSelect: (CameraFile) -> Unit
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

            // 点击看大图，长按选中
            card.setOnClickListener { onOpen(item) }
            card.setOnLongClickListener {
                onSelect(item)
                true
            }

            val key = Thumbs.keyOf(item)
            loadJob?.cancel()
            thumb.tag = key

            val memory = Thumbs.cached(key)
            if (memory != null) {
                thumb.setImageBitmap(memory)
                return
            }
            thumb.setImageResource(R.drawable.ph_photo)

            loadJob = scope.launch {
                val context = itemView.context.applicationContext
                var bytes = Thumbs.diskBytes(context, key)
                if (bytes == null) {
                    val remote = try {
                        loader(item)
                    } catch (e: Exception) {
                        null
                    }
                    if (remote != null) {
                        Thumbs.saveDisk(context, key, remote)
                        bytes = remote
                    }
                }
                val data = bytes ?: return@launch
                val bitmap = withContext(Dispatchers.Default) { Thumbs.decode(data, 480) } ?: return@launch
                Thumbs.remember(key, bitmap)
                if (thumb.tag == key) thumb.setImageBitmap(bitmap)
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

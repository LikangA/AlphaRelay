package com.alpharelay.camera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏看图：双指缩放、拖动、双击回正。
 * 加载过程有进度和失败原因，不是只弹一个「失败」。
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var zoom: ZoomImageView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        zoom = findViewById(R.id.zoom)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        val title = findViewById<TextView>(R.id.title)

        @Suppress("DEPRECATION")
        val file = intent.getSerializableExtra(EXTRA_FILE) as? CameraFile
        if (file == null) {
            finish()
            return
        }
        title.text = file.name
        status.setOnClickListener { load(file) }
        load(file)
    }

    private fun load(file: CameraFile) {
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.text = getString(R.string.preview_loading)

        lifecycleScope.launch {
            when (val result = PreviewRepository.load(this@PreviewActivity, file)) {
                is PreviewResult.Ok -> {
                    val bitmap = withContext(Dispatchers.Default) { Thumbs.decode(result.bytes, 2560) }
                    if (bitmap == null) {
                        showError(getString(R.string.preview_decode_failed))
                    } else {
                        progress.visibility = View.GONE
                        status.visibility = View.GONE
                        zoom.setImageBitmap(bitmap)
                    }
                }
                is PreviewResult.Fail -> showError(result.message)
            }
        }
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = message + "\n\n" + getString(R.string.preview_retry_hint)
    }

    companion object {
        private const val EXTRA_FILE = "file"

        fun intent(context: Context, file: CameraFile): Intent =
            Intent(context, PreviewActivity::class.java).putExtra(EXTRA_FILE, file)
    }
}

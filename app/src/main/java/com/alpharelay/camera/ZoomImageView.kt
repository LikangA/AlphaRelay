package com.alpharelay.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 支持双指缩放 / 拖动 / 双击回正的大图控件。
 * 不引第三方库，避免给构建加依赖。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private var baseScale = 1f
    private var userScale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (userScale * detector.scaleFactor).coerceIn(1f, MAX_SCALE)
                val applied = target / userScale
                userScale = target
                matrix.postScale(applied, applied, detector.focusX, detector.focusY)
                imageMatrix = matrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                fit()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { fit() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val drawable = drawable ?: return
        val bitmapWidth = drawable.intrinsicWidth.toFloat()
        val bitmapHeight = drawable.intrinsicHeight.toFloat()
        if (bitmapWidth <= 0f || bitmapHeight <= 0f || width == 0 || height == 0) return

        val scale = min(width / bitmapWidth, height / bitmapHeight)
        baseScale = scale
        userScale = 1f
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate((width - bitmapWidth * scale) / 2f, (height - bitmapHeight * scale) / 2f)
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (!scaleDetector.isInProgress) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        matrix.postTranslate(dx, dy)
                        imageMatrix = matrix
                        lastX = event.x
                        lastY = event.y
                    }
                }
            }
        }
        return true
    }

    companion object {
        private const val MAX_SCALE = 8f
    }
}

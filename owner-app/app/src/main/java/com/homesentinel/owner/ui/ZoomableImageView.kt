package com.homesentinel.owner.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Interactive pinch-to-zoom and pan-enabled ImageView.
 * Supports:
 * - Pinch-to-zoom in / zoom out (up to 6x)
 * - Double-tap to quick zoom (2.5x) or reset to normal
 * - Smooth drag/pan when zoomed in to inspect intruder details
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixValues = FloatArray(9)
    private val matrixCopy = Matrix()

    private var scaleDetector: ScaleGestureDetector
    private var gestureDetector: GestureDetector

    private var currentScale = 1f
    private val minScale = 1f
    private val maxScale = 6f

    private val lastTouch = PointF()
    private var isDragging = false

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val targetScale = currentScale * scaleFactor

                if (targetScale in minScale..maxScale) {
                    currentScale = targetScale
                    matrixCopy.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                    fixTranslation()
                    imageMatrix = matrixCopy
                }
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.3f) {
                    resetZoom()
                } else {
                    currentScale = 2.5f
                    matrixCopy.postScale(2.5f, 2.5f, e.x, e.y)
                    fixTranslation()
                    imageMatrix = matrixCopy
                }
                return true
            }
        })
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitImageToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { fitImageToView() }
    }

    fun resetZoom() {
        currentScale = 1f
        fitImageToView()
    }

    fun fitImageToView() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return

        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return

        val scale = minOf(width / dw, height / dh)
        val dx = (width - dw * scale) / 2f
        val dy = (height - dh * scale) / 2f

        matrixCopy.reset()
        matrixCopy.postScale(scale, scale)
        matrixCopy.postTranslate(dx, dy)
        imageMatrix = matrixCopy
        currentScale = 1f
    }

    private fun fixTranslation() {
        matrixCopy.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val d = drawable ?: return

        val scaledW = d.intrinsicWidth * matrixValues[Matrix.MSCALE_X]
        val scaledH = d.intrinsicHeight * matrixValues[Matrix.MSCALE_Y]

        var fixX = 0f
        var fixY = 0f

        if (scaledW < width) {
            fixX = (width - scaledW) / 2f - transX
        } else if (transX > 0) {
            fixX = -transX
        } else if (transX + scaledW < width) {
            fixX = width - (transX + scaledW)
        }

        if (scaledH < height) {
            fixY = (height - scaledH) / 2f - transY
        } else if (transY > 0) {
            fixY = -transY
        } else if (transY + scaledH < height) {
            fixY = height - (transY + scaledH)
        }

        matrixCopy.postTranslate(fixX, fixY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && currentScale > 1f) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    matrixCopy.postTranslate(dx, dy)
                    fixTranslation()
                    imageMatrix = matrixCopy
                    isDragging = true
                }
                lastTouch.set(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}

package com.family.photos

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView

class TouchImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private var mode = NONE
    private var last = PointF()
    private var start = PointF()
    private var minScale = 1f
    private var maxScale = 5f
    private var saveScale = 1f
    private var right: Float = 0f
    private var bottom: Float = 0f
    private var origWidth: Float = 0f
    private var origHeight: Float = 0f
    private var bmWidth: Int = 0
    private var bmHeight: Int = 0
    private val scroller = OverScroller(context)
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        super.setScaleType(ScaleType.MATRIX)
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
        imageMatrix = matrix
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val mScaleFactor = detector.scaleFactor
            val origScale = saveScale
            saveScale *= mScaleFactor
            if (saveScale > maxScale) saveScale = maxScale
            if (saveScale < minScale) saveScale = minScale
            if (origScale < minScale) return true
            if (saveScale == origScale) return true
            matrix.postScale(saveScale / origScale, saveScale / origScale, detector.focusX, detector.focusY)
            fixTrans()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (saveScale < 2f) 2.5f else minScale
            val factor = targetScale / saveScale
            matrix.postScale(factor, factor, e.x, e.y)
            saveScale = targetScale
            fixTrans()
            return true
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        bmWidth = drawable?.intrinsicWidth ?: 0
        bmHeight = drawable?.intrinsicHeight ?: 0
        val viewWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (bmWidth == 0 || bmHeight == 0) return
        val scaleX = viewWidth.toFloat() / bmWidth.toFloat()
        val scaleY = viewHeight.toFloat() / bmHeight.toFloat()
        val scale = minOf(scaleX, scaleY)
        matrix.setScale(scale, scale)
        origWidth = scale * bmWidth
        origHeight = scale * bmHeight
        saveScale = 1f
        minScale = 1f
        right = viewWidth.toFloat() - origWidth
        bottom = viewHeight.toFloat() - origHeight
        val transX = viewWidth.toFloat() / 2 - origWidth / 2
        val transY = viewHeight.toFloat() / 2 - origHeight / 2
        matrix.postTranslate(transX, transY)
        imageMatrix = matrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val point = PointF(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                last.set(point)
                start.set(last)
                mode = DRAG
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    val dx = event.x - last.x
                    val dy = event.y - last.y
                    if (saveScale > minScale) {
                        matrix.postTranslate(dx, dy)
                        fixTrans()
                    }
                    last.set(point)
                }
            }
            MotionEvent.ACTION_UP -> {
                mode = NONE
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
        }
        imageMatrix = matrix
        invalidate()
        return true
    }

    private fun fixTrans() {
        matrix.getValues(FloatArray(9))
        val vals = FloatArray(9)
        matrix.getValues(vals)
        val transX = vals[Matrix.MTRANS_X]
        val transY = vals[Matrix.MTRANS_Y]
        val fixTransX = getFixTrans(transX, right, origWidth * saveScale)
        val fixTransY = getFixTrans(transY, bottom, origHeight * saveScale)
        if (fixTransX != 0f || fixTransY != 0f) matrix.postTranslate(fixTransX, fixTransY)
    }

    private fun getFixTrans(trans: Float, origin: Float, size: Float): Float {
        val minTrans: Float
        val maxTrans: Float
        if (size <= width.toFloat()) {
            minTrans = origin / 2 - (size - width) / 2
            maxTrans = minTrans
        } else {
            minTrans = width - size
            maxTrans = 0f
        }
        if (trans < minTrans) return -trans + minTrans
        if (trans > maxTrans) return -trans + maxTrans
        return 0f
    }

    fun resetScale() {
        saveScale = 1f
        requestLayout()
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
    }
}
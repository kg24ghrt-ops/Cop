package com.pot.cil.hj.ui.view

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Pan/zoom container that shows the full A5 page centered,
 * with pinch-to-zoom and pan for comfortable editing.
 */
class PanZoomLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 3.5f
        const val FIT_PAGE_SCALE = 1.0f
    }

    private val matrix = Matrix()
    private val inverseMatrix = Matrix()
    private var scale = 1.0f
    private var transX = 0f
    private var transY = 0f

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private val lastFocus = PointF()
    private var isScaling = false
    private var isPanning = false

    var onTransformChanged: ((Float, Float, Float) -> Unit)? = null
    var onTapAtLocation: ((Float, Float) -> Unit)? = null

    init {
        setWillNotDraw(false)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (childCount > 0 && changed) {
            // Center the A5 page in the view
            centerPage()
        }
    }

    private fun centerPage() {
        if (childCount == 0) return
        val child = getChildAt(0)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val childWidth = child.measuredWidth.toFloat()
        val childHeight = child.measuredHeight.toFloat()

        // Fit page to screen width with some padding
        val fitScale = (viewWidth / childWidth).coerceAtMost(1.0f) * 0.92f
        
        scale = fitScale
        transX = (viewWidth - childWidth * scale) / 2
        transY = (viewHeight - childHeight * scale) / 2
        
        applyTransform()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastFocus.set(ev.x, ev.y)
                false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isScaling = true
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScaling || ev.pointerCount > 1) true
                else {
                    val dx = ev.x - lastFocus.x
                    val dy = ev.y - lastFocus.y
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        isPanning = true
                        true
                    } else false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasPanning = isPanning
                isPanning = false
                isScaling = false
                wasPanning
            }
            else -> false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastFocus.set(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning && !isScaling) {
                    transX += event.x - lastFocus.x
                    transY += event.y - lastFocus.y
                    lastFocus.set(event.x, event.y)
                    applyTransform()
                }
            }
            MotionEvent.ACTION_UP -> {
                isPanning = false
                isScaling = false
                constrainTransform()
            }
        }
        return true
    }

    private fun applyTransform() {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(transX, transY)
        matrix.invert(inverseMatrix)

        for (i in 0 until childCount) {
            getChildAt(i).apply {
                pivotX = 0f
                pivotY = 0f
                scaleX = this@PanZoomLayout.scale
                scaleY = this@PanZoomLayout.scale
                translationX = transX
                translationY = transY
            }
        }
        onTransformChanged?.invoke(scale, transX, transY)
    }

    private fun constrainTransform() {
        if (childCount == 0) return
        val child = getChildAt(0)
        val cw = child.width * scale
        val ch = child.height * scale
        val vw = width.toFloat()
        val vh = height.toFloat()

        val minX = min(0f, vw - cw)
        val maxX = max(0f, vw - cw)
        val minY = min(0f, vh - ch)
        val maxY = max(0f, vh - ch)

        transX = transX.coerceIn(minX - 100f, maxX + 100f)
        transY = transY.coerceIn(minY - 100f, maxY + 100f)
        applyTransform()
    }

    fun resetTransform() = centerPage()

    fun zoomToPoint(targetScale: Float, focusX: Float, focusY: Float) {
        val newScale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val factor = newScale / scale
        transX = focusX - (focusX - transX) * factor
        transY = focusY - (focusY - transY) * factor
        scale = newScale
        applyTransform()
        constrainTransform()
    }

    fun getCurrentScale() = scale

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector) = true.also { isScaling = true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newScale = (scale * d.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val factor = newScale / scale
            transX = d.focusX - (d.focusX - transX) * factor
            transY = d.focusY - (d.focusY - transY) * factor
            scale = newScale
            applyTransform()
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            isScaling = false
            constrainTransform()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scale > 1.5f) {
                // Return to fit
                val child = getChildAt(0)
                (width.toFloat() / child.width * 0.92f).coerceAtMost(1.0f)
            } else 2.5f
            zoomToPoint(target, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val pts = floatArrayOf(e.x, e.y)
            inverseMatrix.mapPoints(pts)
            onTapAtLocation?.invoke(pts[0], pts[1])
            return true
        }
    }
}

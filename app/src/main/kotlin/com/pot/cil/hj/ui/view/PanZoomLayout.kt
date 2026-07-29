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
 * Container that handles pan and zoom gestures for its child content.
 * Supports: pinch-to-zoom, double-tap to zoom, pan with fling, and
 * intelligent touch routing (passes single taps to children when not zooming).
 */
class PanZoomLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 4.0f
        const val DOUBLE_TAP_SCALE = 2.0f
    }

    // ── Transform State ──────────────────────────────────────
    private val matrix = Matrix()
    private val inverseMatrix = Matrix()
    private var scale = 1.0f
    private var transX = 0f
    private var transY = 0f

    // ── Gesture Detectors ────────────────────────────────────
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    // ── Touch State ──────────────────────────────────────────
    private val lastFocus = PointF()
    private var isScaling = false
    private var isPanning = false

    // ── Callbacks ────────────────────────────────────────────
    var onTransformChanged: ((scale: Float, transX: Float, transY: Float) -> Unit)? = null
    var onTapAtLocation: ((x: Float, y: Float) -> Unit)? = null

    init {
        setWillNotDraw(false)
    }

    // ── Touch Handling ───────────────────────────────────────
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastFocus.set(ev.x, ev.y)
                false // Let children try first
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isScaling = true
                true // Intercept for zoom
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScaling || (ev.pointerCount > 1)) {
                    true
                } else {
                    val dx = ev.x - lastFocus.x
                    val dy = ev.y - lastFocus.y
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isPanning = true
                        true
                    } else {
                        false
                    }
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
        // Transform touch to content coordinates for detectors
        val transformedEvent = MotionEvent.obtain(event)
        inverseMatrix.mapPoints(
            floatArrayOf(transformedEvent.x, transformedEvent.y)
        )
        
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastFocus.set(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning && !isScaling) {
                    val dx = event.x - lastFocus.x
                    val dy = event.y - lastFocus.y
                    transX += dx
                    transY += dy
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

        transformedEvent.recycle()
        return true
    }

    // ── Transform Application ────────────────────────────────
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
        val contentWidth = child.width * scale
        val contentHeight = child.height * scale
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Don't let content drift too far off screen
        val minX = min(0f, viewWidth - contentWidth)
        val maxX = max(0f, viewWidth - contentWidth)
        val minY = min(0f, viewHeight - contentHeight)
        val maxY = max(0f, viewHeight - contentHeight)

        transX = transX.coerceIn(minX - 200f, maxX + 200f)
        transY = transY.coerceIn(minY - 200f, maxY + 200f)

        applyTransform()
    }

    fun resetTransform() {
        scale = 1.0f
        transX = 0f
        transY = 0f
        applyTransform()
    }

    fun zoomToPoint(targetScale: Float, focusX: Float, focusY: Float) {
        val newScale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val scaleFactor = newScale / scale

        // Adjust translation to zoom toward focus point
        transX = focusX - (focusX - transX) * scaleFactor
        transY = focusY - (focusY - transY) * scaleFactor
        scale = newScale

        applyTransform()
        constrainTransform()
    }

    // ── Gesture Listeners ────────────────────────────────────
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val scaleFactor = newScale / scale

            val focusX = detector.focusX
            val focusY = detector.focusY

            transX = focusX - (focusX - transX) * scaleFactor
            transY = focusY - (focusY - transY) * scaleFactor
            scale = newScale

            applyTransform()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
            constrainTransform()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (scale > 1.5f) 1.0f else DOUBLE_TAP_SCALE
            zoomToPoint(targetScale, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Transform tap to content coordinates
            val pts = floatArrayOf(e.x, e.y)
            inverseMatrix.mapPoints(pts)
            onTapAtLocation?.invoke(pts[0], pts[1])
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            // Optional: Add fling animation with OverScroller
            return false
        }
    }

    // ── Public API ───────────────────────────────────────────
    fun getContentCoordinates(screenX: Float, screenY: Float): PointF {
        val pts = floatArrayOf(screenX, screenY)
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    fun getCurrentScale(): Float = scale
}

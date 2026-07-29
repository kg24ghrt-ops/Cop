package com.pot.cil.hj

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlin.math.min
import kotlin.random.Random

class NotebookRenderer(private val context: Context) {

    companion object {
        private const val TAG = "NotebookRenderer"
    }

    // ---- Paper specs (in millimeters) ----
    private val LINE_SPACING_MM = 7.1f      // Standard ruled notebook paper spacing[reference:2]
    private val TOP_MARGIN_MM = 32f
    private val LEFT_MARGIN_MM = 32f
    private val BOTTOM_MARGIN_MM = 12.7f
    private val TOTAL_LINES = 32

    // ---- Runtime values ----
    var lineSpacingPx = 30f
        private set
    var topMarginPx = 40f
        private set
    var leftMarginPx = 40f
        private set
    var bottomMarginPx = 16f
        private set
    private var totalLinesValue = TOTAL_LINES

    var selectedLine = 3
        private set
    private val textPerLine = mutableMapOf<Int, String>()

    // Transform (pan/zoom)
    private val transformMatrix = Matrix()
    private val inverseMatrix = Matrix()

    // ---- RenderNode caching (Android 11+ / API 30+) ----
    private val staticRenderNode = RenderNode("NotebookStatic")
    private val dynamicRenderNode = RenderNode("NotebookDynamic")

    // ---- Paints ----
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 153, 191)  // Soft blue
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(191, 51, 51)    // Red
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(77, 102, 153, 255)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }

    // ---- Random seeds for text jitter ----
    private val lineRngSeeds = mutableMapOf<Int, Long>()

    private var viewWidth = 0
    private var viewHeight = 0

    // ---- Flags to track if nodes have been recorded ----
    private var staticNodeReady = false
    private var dynamicNodeReady = false

    init {
        staticRenderNode.setPosition(0, 0, 1, 1)
        dynamicRenderNode.setPosition(0, 0, 1, 1)
    }

    // ---- Public API ----
    fun setTextOnLine(lineNumber: Int, text: String) {
        val safeLine = lineNumber.coerceIn(0, totalLinesValue - 1)
        if (text.isEmpty()) {
            textPerLine.remove(safeLine)
        } else {
            textPerLine[safeLine] = text
        }
        selectedLine = safeLine
        rebuildDynamicNode()
    }

    fun getTextOnLine(lineNumber: Int): String? = textPerLine[lineNumber]

    fun clearAllText() {
        textPerLine.clear()
        lineRngSeeds.clear()
        rebuildDynamicNode()
    }

    fun setSelectedLine(lineNumber: Int) {
        selectedLine = lineNumber.coerceIn(0, totalLinesValue - 1)
        rebuildDynamicNode()
    }

    fun getTotalLines(): Int = totalLinesValue
    fun getLineHeightPixels(): Float = lineSpacingPx
    fun getTopMarginPixels(): Float = topMarginPx
    fun getLeftMarginPixels(): Float = leftMarginPx
    fun getLineSpacingPixels(): Float = lineSpacingPx

    fun setPan(dx: Float, dy: Float) {
        transformMatrix.postTranslate(dx, dy)
        clampPan()
    }

    fun setZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        val pts = floatArrayOf(1f, 0f)
        transformMatrix.mapVectors(pts)
        val currentScale = pts[0]
        val newScale = currentScale * scaleFactor
        if (newScale < 0.5f || newScale > 3.0f) return

        transformMatrix.invert(inverseMatrix)
        val focus = floatArrayOf(focusX, focusY)
        inverseMatrix.mapPoints(focus)
        transformMatrix.postScale(scaleFactor, scaleFactor, focus[0], focus[1])
        clampPan()
    }

    fun resetTransform() {
        transformMatrix.reset()
        clampPan()
    }

    fun onSizeChanged(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height

        Log.d(TAG, "onSizeChanged: width=$width, height=$height")

        // Update RenderNode positions
        staticRenderNode.setPosition(0, 0, width, height)
        dynamicRenderNode.setPosition(0, 0, width, height)

        recalcPaperParams()
        rebuildStaticNode()
        rebuildDynamicNode()
    }

    // ---- Drawing ----
    fun draw(canvas: Canvas, width: Int, height: Int) {
        // If not hardware accelerated, use fallback
        if (!canvas.isHardwareAccelerated) {
            Log.w(TAG, "Canvas not hardware accelerated - using fallback")
            drawFallback(canvas)
            return
        }

        canvas.save()
        canvas.concat(transformMatrix)

        // Draw static RenderNode (background, lines, margin)
        if (staticNodeReady && staticRenderNode.hasDisplayList()) {
            canvas.drawRenderNode(staticRenderNode)
        } else {
            Log.w(TAG, "Static node not ready - redrawing")
            rebuildStaticNode()
            if (staticNodeReady && staticRenderNode.hasDisplayList()) {
                canvas.drawRenderNode(staticRenderNode)
            }
        }

        // Draw dynamic RenderNode (highlight, text)
        if (dynamicNodeReady && dynamicRenderNode.hasDisplayList()) {
            canvas.drawRenderNode(dynamicRenderNode)
        }

        canvas.restore()

        // Vignette (screen space, no transform)
        drawVignette(canvas, width, height)
    }

    // ---- Fallback for non-accelerated canvases ----
    private fun drawFallback(canvas: Canvas) {
        // Paper background
        canvas.drawColor(Color.rgb(250, 245, 230))

        Log.d(TAG, "Fallback: drawing $totalLinesValue lines")

        // Ruled lines
        for (i in 0 until totalLinesValue) {
            val y = topMarginPx + i * lineSpacingPx + lineSpacingPx / 2f
            canvas.drawLine(leftMarginPx, y, viewWidth.toFloat(), y, linePaint)
        }

        // Margin
        canvas.drawLine(
            leftMarginPx,
            topMarginPx,
            leftMarginPx,
            viewHeight - bottomMarginPx,
            marginPaint
        )

        // Highlight
        if (selectedLine in 0 until totalLinesValue) {
            val y = topMarginPx + selectedLine * lineSpacingPx
            canvas.drawRect(
                leftMarginPx,
                y,
                viewWidth.toFloat(),
                y + lineSpacingPx,
                highlightPaint
            )
        }

        // Text (simplified, without jitter)
        for ((line, text) in textPerLine) {
            if (line !in 0 until totalLinesValue) continue
            val y = topMarginPx + line * lineSpacingPx + lineSpacingPx / 2f
            val fm = textPaint.fontMetrics
            val baseline = y - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, leftMarginPx + 10f, baseline, textPaint)
        }
    }

    // ---- Private helpers ----

    private fun recalcPaperParams() {
        val dpi = context.resources.displayMetrics.densityDpi.toFloat()
        val pxPerMm = dpi / 25.4f

        Log.d(TAG, "recalcPaperParams: dpi=$dpi, pxPerMm=$pxPerMm")

        // Calculate line spacing - use the smaller of the two values
        val calcSpacing = LINE_SPACING_MM * pxPerMm
        val viewBasedSpacing = if (TOTAL_LINES > 0) {
            (viewHeight - (TOP_MARGIN_MM + BOTTOM_MARGIN_MM) * pxPerMm) / TOTAL_LINES
        } else {
            calcSpacing
        }

        lineSpacingPx = if (calcSpacing > 0 && viewBasedSpacing > 0) {
            min(calcSpacing, viewBasedSpacing)
        } else if (calcSpacing > 0) {
            calcSpacing
        } else {
            30f // fallback
        }

        topMarginPx = TOP_MARGIN_MM * pxPerMm
        leftMarginPx = (LEFT_MARGIN_MM * pxPerMm).coerceAtMost(viewWidth * 0.3f)
        bottomMarginPx = BOTTOM_MARGIN_MM * pxPerMm

        // Ensure we have valid margins
        if (topMarginPx <= 0) topMarginPx = 40f
        if (leftMarginPx <= 0) leftMarginPx = 40f
        if (bottomMarginPx <= 0) bottomMarginPx = 16f

        val availableHeight = viewHeight - topMarginPx - bottomMarginPx
        totalLinesValue = if (lineSpacingPx > 0) {
            (availableHeight / lineSpacingPx).toInt().coerceIn(1, TOTAL_LINES)
        } else {
            TOTAL_LINES
        }

        textPaint.textSize = lineSpacingPx * 0.5f

        // Clamp selection
        selectedLine = selectedLine.coerceIn(0, totalLinesValue - 1)
        textPerLine.keys.removeAll { it !in 0 until totalLinesValue }

        Log.d(TAG, "recalcPaperParams: lineSpacingPx=$lineSpacingPx, " +
                "topMarginPx=$topMarginPx, leftMarginPx=$leftMarginPx, " +
                "bottomMarginPx=$bottomMarginPx, totalLines=$totalLinesValue")
    }

    private fun rebuildStaticNode() {
        if (viewWidth <= 0 || viewHeight <= 0) {
            Log.w(TAG, "rebuildStaticNode: invalid dimensions")
            return
        }

        Log.d(TAG, "rebuildStaticNode: recording ${viewWidth}x${viewHeight}, lines=$totalLinesValue")

        val canvas = staticRenderNode.beginRecording(viewWidth, viewHeight)
        try {
            // ---- Paper background color ----
            canvas.drawColor(Color.rgb(250, 245, 230))

            // ---- TEST: Draw a red rectangle to confirm rendering works ----
            val testPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.FILL
            }
            canvas.drawRect(50f, 50f, 150f, 150f, testPaint)

            // ---- Ruled lines (blue) ----
            for (i in 0 until totalLinesValue) {
                val y = topMarginPx + i * lineSpacingPx + lineSpacingPx / 2f
                canvas.drawLine(leftMarginPx, y, viewWidth.toFloat(), y, linePaint)
            }

            // ---- Red margin ----
            canvas.drawLine(
                leftMarginPx,
                topMarginPx,
                leftMarginPx,
                viewHeight - bottomMarginPx,
                marginPaint
            )

            staticNodeReady = true
            Log.d(TAG, "rebuildStaticNode: completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "rebuildStaticNode error", e)
            staticNodeReady = false
        } finally {
            staticRenderNode.endRecording()
        }
    }

    private fun rebuildDynamicNode() {
        if (viewWidth <= 0 || viewHeight <= 0) {
            Log.w(TAG, "rebuildDynamicNode: invalid dimensions")
            return
        }

        val canvas = dynamicRenderNode.beginRecording(viewWidth, viewHeight)
        try {
            // Clear to transparent
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)

            // ---- Selected line highlight ----
            if (selectedLine in 0 until totalLinesValue) {
                val y = topMarginPx + selectedLine * lineSpacingPx
                canvas.drawRect(
                    leftMarginPx,
                    y,
                    viewWidth.toFloat(),
                    y + lineSpacingPx,
                    highlightPaint
                )
            }

            // ---- Text with humanization ----
            for ((line, text) in textPerLine) {
                if (line !in 0 until totalLinesValue) continue
                drawHumanizedText(canvas, text, line)
            }

            dynamicNodeReady = true
        } catch (e: Exception) {
            Log.e(TAG, "rebuildDynamicNode error", e)
            dynamicNodeReady = false
        } finally {
            dynamicRenderNode.endRecording()
        }
    }

    private fun drawHumanizedText(canvas: Canvas, text: String, line: Int) {
        if (!lineRngSeeds.containsKey(line)) {
            lineRngSeeds[line] = Random.nextLong()
        }
        val rng = Random(lineRngSeeds[line]!!)

        val baseX = leftMarginPx + 10f
        val lineTop = topMarginPx + line * lineSpacingPx
        val baseY = lineTop + lineSpacingPx / 2f
        val fm = textPaint.fontMetrics
        val idealBaseline = baseY - (fm.ascent + fm.descent) / 2f

        var x = baseX
        for (char in text) {
            val charStr = char.toString()
            val charWidth = textPaint.measureText(charStr)

            val maxJitterY = lineSpacingPx * 0.15f
            val jitterY = (rng.nextFloat() * 2f - 1f) * maxJitterY * 0.6f

            val maxRotation = 2f
            val rotation = (rng.nextFloat() * 2f - 1f) * maxRotation * 0.6f

            val spacingVariation = 1f + (rng.nextFloat() * 2f - 1f) * 0.15f * 0.6f
            val actualAdvance = charWidth * spacingVariation

            val baseAlpha = (0.7f + rng.nextFloat() * 0.3f) * (1f - 0.6f * 0.3f)
            textPaint.alpha = (baseAlpha * 255).toInt().coerceIn(0, 255)

            canvas.save()
            canvas.translate(x, idealBaseline + jitterY)
            canvas.rotate(rotation, 0f, 0f)
            canvas.drawText(charStr, 0f, 0f, textPaint)
            canvas.restore()

            x += actualAdvance
        }
        textPaint.alpha = 255
    }

    private fun drawVignette(canvas: Canvas, width: Int, height: Int) {
        val cx = width / 2f
        val cy = height / 2f
        val r = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat() * 0.9f
        val paint = Paint().apply {
            shader = RadialGradient(
                cx, cy, r,
                intArrayOf(Color.TRANSPARENT, Color.argb(30, 0, 0, 0)),
                floatArrayOf(0.7f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun clampPan() {
        val values = FloatArray(9)
        transformMatrix.getValues(values)
        // Basic clamp to keep content visible
        val tx = values[2]
        val ty = values[5]
        val scale = values[0]
        val maxTx = viewWidth * 0.5f
        val maxTy = viewHeight * 0.5f
        if (tx > maxTx) transformMatrix.postTranslate(-(tx - maxTx), 0f)
        if (tx < -maxTx) transformMatrix.postTranslate(-(tx + maxTx), 0f)
        if (ty > maxTy) transformMatrix.postTranslate(0f, -(ty - maxTy))
        if (ty < -maxTy) transformMatrix.postTranslate(0f, -(ty + maxTy))
    }
}
package com.pot.cil.hj

import android.content.Context
import android.graphics.*
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

class MyGLRenderer(private val context: Context) {

    private val LINE_SPACING_MM = 7.1f
    private val TOP_MARGIN_MM = 32f
    private val LEFT_MARGIN_MM = 32f
    private val BOTTOM_MARGIN_MM = 12.7f
    private val TOTAL_LINES = 32

    // Layout in pixels (fixed, based on view size)
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

    // Current view dimensions (paper coordinate space size)
    private var viewWidth = 0
    private var viewHeight = 0

    // Transform for pan/zoom
    private val contentMatrix = Matrix()
    private val invertedMatrix = Matrix()

    // Paints
    private val paperPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 153, 191)
        strokeWidth = 1.5f
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(191, 51, 51)
        strokeWidth = 2f
    }
    private val selectedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(77, 102, 153, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.LEFT
        textSize = 40f
    }
    private val vignettePaint = Paint()
    private val noisePaint = Paint().apply { alpha = 20 }
    private var noiseBitmap: Bitmap? = null

    fun onSurfaceChanged(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        viewWidth = width
        viewHeight = height

        val dpi = context.resources.displayMetrics.densityDpi.toFloat()
        val pxPerMm = dpi / 25.4f

        val calcSpacing = LINE_SPACING_MM * pxPerMm
        val viewBasedSpacing = (height - (TOP_MARGIN_MM + BOTTOM_MARGIN_MM) * pxPerMm) / TOTAL_LINES
        lineSpacingPx = min(calcSpacing, viewBasedSpacing)
        topMarginPx = TOP_MARGIN_MM * pxPerMm
        leftMarginPx = (LEFT_MARGIN_MM * pxPerMm).coerceAtMost(width * 0.3f)
        bottomMarginPx = BOTTOM_MARGIN_MM * pxPerMm

        val availableHeight = height - topMarginPx - bottomMarginPx
        totalLinesValue = (availableHeight / lineSpacingPx).toInt().coerceAtMost(TOTAL_LINES)
        textPaint.textSize = lineSpacingPx * 0.5f

        // reset transform to identity (paper fills view)
        contentMatrix.reset()
    }

    fun onDrawFrame(canvas: Canvas) {
        if (viewWidth == 0 || viewHeight == 0) return

        // Draw paper content with transform
        canvas.save()
        canvas.concat(contentMatrix)

        // paper background
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(),
            Paint().apply { color = Color.rgb(250, 245, 230) })

        // blue lines
        for (i in 0 until totalLinesValue) {
            val y = topMarginPx + i * lineSpacingPx + lineSpacingPx / 2f
            canvas.drawLine(leftMarginPx, y, viewWidth.toFloat(), y, linePaint)
        }

        // red margin line
        canvas.drawLine(leftMarginPx, topMarginPx, leftMarginPx,
            viewHeight - bottomMarginPx, marginPaint)

        // selected line highlight
        if (selectedLine in 0 until totalLinesValue) {
            val y = topMarginPx + selectedLine * lineSpacingPx
            canvas.drawRect(leftMarginPx, y, viewWidth.toFloat(), y + lineSpacingPx,
                selectedLinePaint)
        }

        // text
        for ((line, text) in textPerLine) {
            if (line !in 0 until totalLinesValue) continue
            val x = leftMarginPx + 10f
            val lineTop = topMarginPx + line * lineSpacingPx
            val lineCenter = lineTop + lineSpacingPx / 2f
            val fm = textPaint.fontMetrics
            val baseline = lineCenter - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, x, baseline, textPaint)
        }

        canvas.restore()

        // Screen‑space overlays (vignette, noise) — unaffected by pan/zoom
        drawVignette(canvas)
        drawNoise(canvas)
    }

    private fun drawVignette(canvas: Canvas) {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val r = hypot(cx, cy) * 0.9f
        vignettePaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.TRANSPARENT, Color.argb(30, 0, 0, 0)),
            floatArrayOf(0.7f, 1.0f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), vignettePaint)
        vignettePaint.shader = null
    }

    private fun drawNoise(canvas: Canvas) {
        if (noiseBitmap == null) noiseBitmap = generateNoiseBitmap()
        noiseBitmap?.let {
            canvas.drawBitmap(it, null, Rect(0, 0, viewWidth, viewHeight), noisePaint)
        }
    }

    // ---- Transform manipulation ----
    fun setPan(dx: Float, dy: Float) {
        contentMatrix.postTranslate(dx, dy)
    }

    fun setZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        // Invert current matrix to find the focus point in paper coordinates
        contentMatrix.invert(invertedMatrix)
        val pts = floatArrayOf(focusX, focusY)
        invertedMatrix.mapPoints(pts)
        // Zoom around that paper point
        contentMatrix.postScale(scaleFactor, scaleFactor, pts[0], pts[1])
    }

    // ---- Text API ----
    fun setTextOnLine(lineNumber: Int, text: String) {
        val safeLine = lineNumber.coerceIn(0, totalLinesValue - 1)
        if (text.isEmpty()) {
            textPerLine.remove(safeLine)
        } else {
            textPerLine[safeLine] = text
        }
        selectedLine = safeLine
    }

    fun getTextOnLine(lineNumber: Int): String? = textPerLine[lineNumber]

    fun clearAllText() {
        textPerLine.clear()
    }

    fun setSelectedLine(lineNumber: Int) {
        selectedLine = lineNumber.coerceIn(0, totalLinesValue - 1)
    }

    fun getTotalLines(): Int = totalLinesValue
    fun getLineHeightPixels(): Float = lineSpacingPx
    fun getTopMarginPixels(): Float = topMarginPx
    fun getLeftMarginPixels(): Float = leftMarginPx
    fun getLineSpacingPixels(): Float = lineSpacingPx

    private fun generateNoiseBitmap(): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val rng = Random(12345)
        for (i in pixels.indices) {
            val gray = rng.nextInt(256)
            pixels[i] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        noisePaint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        return bmp
    }

    fun cleanup() {
        noiseBitmap?.recycle()
    }
}
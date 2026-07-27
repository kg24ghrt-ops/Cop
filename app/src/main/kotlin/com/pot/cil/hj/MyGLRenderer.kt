package com.pot.cil.hj

import android.content.Context
import android.graphics.*
import kotlin.math.min
import kotlin.random.Random
import kotlin.math.hypot      

class MyGLRenderer(private val context: Context) {

    // ---- Paper specifications ----
    private val LINE_SPACING_MM = 7.1f
    private val TOP_MARGIN_MM = 32f
    private val LEFT_MARGIN_MM = 32f
    private val BOTTOM_MARGIN_MM = 12.7f
    private val TOTAL_LINES = 32

    // ---- Runtime pixel values ----
    var lineSpacingPx = 30f
        private set
    var topMarginPx = 40f
        private set
    var leftMarginPx = 40f
        private set
    var bottomMarginPx = 16f
        private set
    var totalLines = TOTAL_LINES
        private set

    // ---- Text storage ----
    private val textPerLine = mutableMapOf<Int, String>()
    var selectedLine = 3
        private set

    // ---- Drawing caches ----
    private var linesBitmap: Bitmap? = null
    private var textOverlayBitmap: Bitmap? = null
    private var noiseBitmap: Bitmap? = null
    private var viewWidth = 0
    private var viewHeight = 0

    // ---- Paints ----
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
        totalLines = (availableHeight / lineSpacingPx).toInt().coerceAtMost(TOTAL_LINES)
        textPaint.textSize = lineSpacingPx * 0.5f

        rebuildLinesBitmap()
        redrawTextOverlay()
    }

    fun onDrawFrame(canvas: Canvas) {
        if (viewWidth == 0 || viewHeight == 0) return

        canvas.drawColor(Color.rgb(250, 245, 230))

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

        if (noiseBitmap == null) noiseBitmap = generateNoiseBitmap()
        noiseBitmap?.let { canvas.drawBitmap(it, null, Rect(0, 0, viewWidth, viewHeight), noisePaint) }

        linesBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        if (selectedLine in 0 until totalLines) {
            val y = topMarginPx + selectedLine * lineSpacingPx
            canvas.drawRect(leftMarginPx, y, viewWidth.toFloat(), y + lineSpacingPx, selectedLinePaint)
        }

        textOverlayBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    fun setTextOnLine(lineNumber: Int, text: String) {
        val safeLine = lineNumber.coerceIn(0, totalLines - 1)
        if (text.isEmpty()) {
            textPerLine.remove(safeLine)
        } else {
            textPerLine[safeLine] = text
        }
        selectedLine = safeLine
        redrawTextOverlay()
    }

    fun getTextOnLine(lineNumber: Int): String? = textPerLine[lineNumber]

    fun clearAllText() {
        textPerLine.clear()
        redrawTextOverlay()
    }

    fun setSelectedLine(lineNumber: Int) {
        selectedLine = lineNumber.coerceIn(0, totalLines - 1)
    }

    fun getTotalLines(): Int = totalLines
    fun getLineHeightPixels(): Float = lineSpacingPx
    fun getTopMarginPixels(): Float = topMarginPx
    fun getLeftMarginPixels(): Float = leftMarginPx
    fun getLineSpacingPixels(): Float = lineSpacingPx

    private fun rebuildLinesBitmap() {
        if (viewWidth <= 0 || viewHeight <= 0) return
        linesBitmap?.recycle()
        linesBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(linesBitmap!!)
        for (i in 0 until totalLines) {
            val y = topMarginPx + i * lineSpacingPx + lineSpacingPx / 2f
            c.drawLine(leftMarginPx, y, viewWidth.toFloat(), y, linePaint)
        }
        c.drawLine(leftMarginPx, topMarginPx, leftMarginPx, viewHeight - bottomMarginPx, marginPaint)
    }

    private fun redrawTextOverlay() {
        if (viewWidth <= 0 || viewHeight <= 0) return
        textOverlayBitmap?.recycle()
        textOverlayBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(textOverlayBitmap!!)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for ((line, text) in textPerLine) {
            if (line !in 0 until totalLines) continue
            val x = leftMarginPx + 10f
            val lineTop = topMarginPx + line * lineSpacingPx
            val lineCenter = lineTop + lineSpacingPx / 2f
            val fm = textPaint.fontMetrics
            val baseline = lineCenter - (fm.ascent + fm.descent) / 2f
            c.drawText(text, x, baseline, textPaint)
        }
    }

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
        linesBitmap?.recycle()
        textOverlayBitmap?.recycle()
        noiseBitmap?.recycle()
    }
}
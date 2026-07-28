package com.pot.cil.hj

import android.content.Context
import android.graphics.*
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random
import kotlin.math.max 

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
    private var totalLinesValue = TOTAL_LINES

    var selectedLine = 3
        private set
    private val textPerLine = mutableMapOf<Int, String>()

    // Current view dimensions
    private var viewWidth = 0
    private var viewHeight = 0

    // Transform for pan/zoom
    private val contentMatrix = Matrix()
    private val invertedMatrix = Matrix()

    // Humanization factor
    var humanizeFactor = 0.6f

    // Per‑line cached bitmaps of rendered text (speed!)
    private val lineBitmapCache = mutableMapOf<Int, Bitmap>()
    // Seeds per line to keep randomness stable
    private val lineRngSeeds = mutableMapOf<Int, Long>()

    // Paints
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
        isSubpixelText = true
    }
    private val vignettePaint = Paint()
    private val paperGrainPaint = Paint().apply { alpha = 45 }  // stronger grain
    private var paperGrainBitmap: Bitmap? = null

    // ---- Layout change (called when view size changes) ----
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

        // Clamp selection
        selectedLine = selectedLine.coerceIn(0, totalLinesValue - 1)

        // Remove text for lines that no longer exist
        textPerLine.keys.removeAll { it !in 0 until totalLinesValue }
        // Clear bitmap cache for those removed lines
        lineBitmapCache.keys.removeAll { it !in 0 until totalLinesValue }

        // Regenerate all cached bitmaps for existing lines (size may have changed)
        rebuildAllLineBitmaps()

        contentMatrix.reset()
    }

    // ---- Main draw (called every frame) ----
    fun onDrawFrame(canvas: Canvas) {
        if (viewWidth == 0 || viewHeight == 0) return

        // 1. Paper content with pan/zoom
        canvas.save()
        canvas.concat(contentMatrix)

        // ----- Paper surface (inside transform) -----
        // a) Base color
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(),
            Paint().apply { color = Color.rgb(250, 245, 230) })

        // b) Paper grain (replaces the old noise overlay)
        drawPaperGrain(canvas)

        // c) Ruled lines
        for (i in 0 until totalLinesValue) {
            val y = topMarginPx + i * lineSpacingPx + lineSpacingPx / 2f
            canvas.drawLine(leftMarginPx, y, viewWidth.toFloat(), y, linePaint)
        }

        // d) Red margin
        canvas.drawLine(leftMarginPx, topMarginPx, leftMarginPx,
            viewHeight - bottomMarginPx, marginPaint)

        // e) Selected line highlight
        if (selectedLine in 0 until totalLinesValue) {
            val y = topMarginPx + selectedLine * lineSpacingPx
            canvas.drawRect(leftMarginPx, y, viewWidth.toFloat(), y + lineSpacingPx,
                selectedLinePaint)
        }

        // f) Cached text bitmaps (blit only – very fast)
        for ((line, bitmap) in lineBitmapCache) {
            if (line !in 0 until totalLinesValue) continue
            val destX = leftMarginPx + 10f
            val destY = topMarginPx + line * lineSpacingPx
            canvas.drawBitmap(bitmap, destX, destY, null)
        }

        canvas.restore()

        // 2. Screen‑space vignette (camera effect)
        drawVignette(canvas)
    }

    // ---- Paper grain (inside paper coordinate space) ----
    private fun drawPaperGrain(canvas: Canvas) {
        if (paperGrainBitmap == null) paperGrainBitmap = generatePaperGrainBitmap()
        paperGrainBitmap?.let {
            // Tile the grain across the entire paper area
            canvas.drawBitmap(it, null, Rect(0, 0, viewWidth, viewHeight), paperGrainPaint)
        }
    }

    // ---- Text API (unchanged) ----
    fun setTextOnLine(lineNumber: Int, text: String) {
        val safeLine = lineNumber.coerceIn(0, totalLinesValue - 1)
        if (text.isEmpty()) {
            textPerLine.remove(safeLine)
        } else {
            textPerLine[safeLine] = text
        }
        selectedLine = safeLine
        // Regenerate the bitmap for this line only
        rebuildLineBitmap(safeLine)
    }

    fun getTextOnLine(lineNumber: Int): String? = textPerLine[lineNumber]

    fun clearAllText() {
        textPerLine.clear()
        lineBitmapCache.clear()
        lineRngSeeds.clear()
    }

    fun setSelectedLine(lineNumber: Int) {
        selectedLine = lineNumber.coerceIn(0, totalLinesValue - 1)
    }

    // ---- Transform manipulation ----
    fun setPan(dx: Float, dy: Float) {
        contentMatrix.postTranslate(dx, dy)
        clampPan()
    }

    fun setZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        val pts = floatArrayOf(1f, 0f)
        contentMatrix.mapVectors(pts)
        val currentScale = pts[0]
        val newScale = currentScale * scaleFactor
        if (newScale < 0.5f || newScale > 3.0f) return

        contentMatrix.invert(invertedMatrix)
        val focus = floatArrayOf(focusX, focusY)
        invertedMatrix.mapPoints(focus)
        contentMatrix.postScale(scaleFactor, scaleFactor, focus[0], focus[1])
        clampPan()
    }

    fun resetTransform() {
        contentMatrix.reset()
    }

    // ---- Public getters ----
    fun getTotalLines(): Int = totalLinesValue
    fun getLineHeightPixels(): Float = lineSpacingPx
    fun getTopMarginPixels(): Float = topMarginPx
    fun getLeftMarginPixels(): Float = leftMarginPx
    fun getLineSpacingPixels(): Float = lineSpacingPx

    // ---- Private: line bitmap caching ----
    private fun rebuildLineBitmap(line: Int) {
        // Remove old bitmap
        lineBitmapCache[line]?.recycle()
        val text = textPerLine[line]
        if (text == null || text.isEmpty()) {
            lineBitmapCache.remove(line)
            return
        }

        // Create a bitmap just large enough for the line height
        val lineHeight = lineSpacingPx.toInt()
        // Width is from left margin to right edge of view (but we can use a safe estimate)
        val maxTextWidth = viewWidth - leftMarginPx.toInt() - 10
        val bitmap = Bitmap.createBitmap(maxTextWidth, lineHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)

        // Humanized text drawing (character by character only during cache creation)
        if (!lineRngSeeds.containsKey(line)) {
            lineRngSeeds[line] = Random.nextLong()
        }
        val rng = Random(lineRngSeeds[line]!!)

        val fm = textPaint.fontMetrics
        val baseline = lineHeight / 2f - (fm.ascent + fm.descent) / 2f
        var x = 0f

        for (char in text) {
            val charStr = char.toString()
            val charWidth = textPaint.measureText(charStr)

            val maxJitterY = lineSpacingPx * 0.15f
            val jitterY = (rng.nextFloat() * 2f - 1f) * maxJitterY * humanizeFactor

            val maxRotation = 2f
            val rotation = (rng.nextFloat() * 2f - 1f) * maxRotation * humanizeFactor

            val spacingVariation = 1f + (rng.nextFloat() * 2f - 1f) * 0.15f * humanizeFactor
            val actualAdvance = charWidth * spacingVariation

            val baseAlpha = (0.7f + rng.nextFloat() * 0.3f) * (1f - humanizeFactor * 0.3f)
            textPaint.alpha = (baseAlpha * 255).toInt().coerceIn(0, 255)

            c.save()
            c.translate(x, baseline + jitterY)
            c.rotate(rotation, 0f, 0f)
            c.drawText(charStr, 0f, 0f, textPaint)
            c.restore()

            x += actualAdvance
        }
        textPaint.alpha = 255   // reset

        lineBitmapCache[line] = bitmap
    }

    private fun rebuildAllLineBitmaps() {
        lineBitmapCache.values.forEach { it.recycle() }
        lineBitmapCache.clear()
        for (line in textPerLine.keys) {
            rebuildLineBitmap(line)
        }
    }

    // ---- Vignette (screen space) ----
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

    // ---- Paper grain generation (once) ----
    private fun generatePaperGrainBitmap(): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val rng = Random(67890)
        // Fill with subtle grey variations that mimic paper fibers
        for (i in pixels.indices) {
            val base = 240 + rng.nextInt(16)   // light grey base
            val noise = rng.nextInt(8) - 4      // tiny variation
            val gray = (base + noise).coerceIn(0, 255)
            pixels[i] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)

        // Slight blur for a softer, more natural grain
        val blurred = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val blurCanvas = Canvas(blurred)
        val blurPaint = Paint().apply {
            maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)
        }
        blurCanvas.drawBitmap(bmp, 0f, 0f, blurPaint)
        bmp.recycle()

        paperGrainPaint.shader = BitmapShader(blurred, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        return blurred
    }

    // ---- Pan clamping ----
    private fun clampPan() {
        val pts = floatArrayOf(0f, 0f, viewWidth.toFloat(), 0f, 0f, viewHeight.toFloat(), viewWidth.toFloat(), viewHeight.toFloat())
        contentMatrix.mapPoints(pts)
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (i in pts.indices step 2) {
            minX = min(minX, pts[i])
            maxX = max(maxX, pts[i])
            minY = min(minY, pts[i + 1])
            maxY = max(maxY, pts[i + 1])
        }
        val dx = if (maxX < viewWidth * 0.2f) viewWidth * 0.2f - maxX
                 else if (minX > viewWidth * 0.8f) viewWidth * 0.8f - minX
                 else 0f
        val dy = if (maxY < viewHeight * 0.2f) viewHeight * 0.2f - maxY
                 else if (minY > viewHeight * 0.8f) viewHeight * 0.8f - minY
                 else 0f
        if (dx != 0f || dy != 0f) {
            contentMatrix.postTranslate(dx, dy)
        }
    }

    fun cleanup() {
        lineBitmapCache.values.forEach { it.recycle() }
        lineBitmapCache.clear()
        paperGrainBitmap?.recycle()
        paperGrainBitmap = null
        lineRngSeeds.clear()
    }
}
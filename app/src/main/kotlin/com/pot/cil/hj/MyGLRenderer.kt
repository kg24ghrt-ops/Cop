package com.pot.cil.hj

import android.content.Context
import android.graphics.*
import kotlin.math.*
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

    // Per‑line cached bitmaps of rendered text
    private val lineBitmapCache = mutableMapOf<Int, Bitmap>()
    // Seeds per line for stable randomness
    private val lineRngSeeds = mutableMapOf<Int, Long>()

    // Pre‑rendered static paper background (background color + grain + lines)
    private var paperBackgroundBitmap: Bitmap? = null

    // Paints
    private val headerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 120, 160)  // slightly darker blue for header/footer
        strokeWidth = 2f
    }
    private val ruleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 153, 191)  // standard blue lines
        strokeWidth = 1.5f
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 50, 50)    // deep red margin
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
    private val paperGrainPaint = Paint().apply { alpha = 50 }   // visible grain
    private var paperGrainBitmap: Bitmap? = null

    // ---- Layout change ----
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
        lineBitmapCache.keys.removeAll { it !in 0 until totalLinesValue }

        // Rebuild static paper background and all text bitmaps
        rebuildPaperBackground()
        rebuildAllLineBitmaps()

        contentMatrix.reset()
    }

    // ---- Main draw ----
    fun onDrawFrame(canvas: Canvas) {
        if (viewWidth == 0 || viewHeight == 0) return

        // 1. Paper content with pan/zoom
        canvas.save()
        canvas.concat(contentMatrix)

        // Pre‑rendered static background (background + grain + lines + margin)
        paperBackgroundBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Selected line highlight (on top of background)
        if (selectedLine in 0 until totalLinesValue) {
            val y = topMarginPx + selectedLine * lineSpacingPx
            canvas.drawRect(leftMarginPx, y, viewWidth.toFloat(), y + lineSpacingPx,
                selectedLinePaint)
        }

        // Cached text bitmaps
        for ((line, bitmap) in lineBitmapCache) {
            if (line !in 0 until totalLinesValue) continue
            val destX = leftMarginPx + 10f
            val destY = topMarginPx + line * lineSpacingPx
            canvas.drawBitmap(bitmap, destX, destY, null)
        }

        canvas.restore()

        // 2. Screen‑space vignette
        drawVignette(canvas)
    }

    // ---- Build the static paper background once ----
    private fun rebuildPaperBackground() {
        paperBackgroundBitmap?.recycle()
        if (viewWidth <= 0 || viewHeight <= 0) return
        paperBackgroundBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(paperBackgroundBitmap!!)

        // Base paper color (warm off‑white)
        c.drawColor(Color.rgb(255, 251, 240))

        // Paper grain (tiled across the paper)
        drawPaperGrain(c)

        // Draw notebook lines with subtle waviness (using fixed seed for consistency)
        val rng = Random(42)   // same seed → same waviness every time
        for (i in 0 until totalLinesValue) {
            val baseY = topMarginPx + i * lineSpacingPx + lineSpacingPx / 2f

            // School notebooks often have a thicker line at the top (header) and bottom (footer)
            val paint = if (i == 0 || i == totalLinesValue - 1) headerLinePaint else ruleLinePaint

            // Generate a slightly wavy path for this line
            val path = Path()
            path.moveTo(leftMarginPx, baseY)
            var x = leftMarginPx
            while (x < viewWidth) {
                // tiny random vertical offset, scaled by humanizeFactor (but always present to some degree)
                val wave = rng.nextFloat() * 1.5f - 0.75f   // ±0.75 px
                path.lineTo(x, baseY + wave * humanizeFactor)
                x += 12f   // sample every 12 pixels – cheap and subtle
            }
            c.drawPath(path, paint)
        }

        // Red margin line (slightly wavy too)
        val marginPath = Path()
        marginPath.moveTo(leftMarginPx, topMarginPx)
        var y = topMarginPx
        while (y < viewHeight - bottomMarginPx) {
            val wave = rng.nextFloat() * 1.0f - 0.5f
            marginPath.lineTo(leftMarginPx + wave * humanizeFactor, y)
            y += 12f
        }
        c.drawPath(marginPath, marginPaint)
    }

    private fun drawPaperGrain(canvas: Canvas) {
        if (paperGrainBitmap == null) paperGrainBitmap = generatePaperGrainBitmap()
        paperGrainBitmap?.let {
            canvas.drawBitmap(it, null, Rect(0, 0, viewWidth, viewHeight), paperGrainPaint)
        }
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

    // ---- Human‑like baseline jitter model ----
    private fun getJitterParams(line: Int): JitterParams {
        // Generate stable parameters from line number
        val seed = lineRngSeeds.getOrPut(line) { Random.nextLong() }
        val rng = Random(seed)

        // Amplitude of slow sinusoidal drift (0 .. 1.5 px)
        val amplitude = rng.nextFloat() * 1.5f
        // Wavelength (how many pixels until the sine repeats) between 200 and 600 px
        val wavelength = 200f + rng.nextFloat() * 400f
        // Random phase shift
        val phase = rng.nextFloat() * 2f * PI.toFloat()
        // Fatigue slope: gradual downward drift (negative) as hand gets tired
        // typically -0.2 to 0.2 degrees, converted to px per 1000px
        val fatigueSlope = (rng.nextFloat() - 0.5f) * 0.4f   // -0.2 to +0.2 px per 1000px

        return JitterParams(amplitude, wavelength, phase, fatigueSlope, rng)
    }

    data class JitterParams(
        val amplitude: Float,
        val wavelength: Float,
        val phase: Float,
        val fatigueSlope: Float,
        val rng: Random
    )

    private fun computeBaselineOffset(x: Float, params: JitterParams): Float {
        // Slow sine drift + fatigue slope + tiny random noise
        val sineDrift = params.amplitude * sin(2.0 * PI * (x / params.wavelength) + params.phase).toFloat()
        val fatigue = params.fatigueSlope * (x / 1000f)   // per 1000px
        val noise = (params.rng.nextFloat() - 0.5f) * 0.3f   // ±0.15 px micro‑jitter
        return sineDrift + fatigue + noise
    }

    // ---- Line bitmap caching (humanized text) ----
    private fun rebuildLineBitmap(line: Int) {
        lineBitmapCache[line]?.recycle()
        val text = textPerLine[line]
        if (text == null || text.isEmpty()) {
            lineBitmapCache.remove(line)
            return
        }

        val lineHeight = lineSpacingPx.toInt()
        val maxTextWidth = (viewWidth - leftMarginPx.toInt() - 10).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(maxTextWidth, lineHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Get jitter parameters for this line
        val jitterParams = getJitterParams(line)

        val fm = textPaint.fontMetrics
        val idealBaseline = lineHeight / 2f - (fm.ascent + fm.descent) / 2f

        var x = 0f
        val chars = text.toCharArray()

        // Pre‑compute all character widths for spacing control
        val charWidths = chars.map { textPaint.measureText(it.toString()) }

        for ((index, char) in chars.withIndex()) {
            val charStr = char.toString()
            val charWidth = charWidths[index]

            // Baseline offset from human model (function of x position)
            val baselineOffset = computeBaselineOffset(x, jitterParams) * humanizeFactor

            // Small rotation jitter (still random, but subtle)
            val rotation = (jitterParams.rng.nextFloat() - 0.5f) * 1.5f * humanizeFactor

            // Spacing variation correlated with drift? Just mild random
            val spacingVariation = 1f + (jitterParams.rng.nextFloat() - 0.5f) * 0.1f * humanizeFactor
            val actualAdvance = charWidth * spacingVariation

            // Ink opacity (subtle variation)
            val baseAlpha = (0.75f + jitterParams.rng.nextFloat() * 0.2f) * (1f - humanizeFactor * 0.2f)
            textPaint.alpha = (baseAlpha * 255).toInt().coerceIn(0, 255)

            canvas.save()
            canvas.translate(x, idealBaseline + baselineOffset)
            canvas.rotate(rotation, 0f, 0f)
            canvas.drawText(charStr, 0f, 0f, textPaint)
            canvas.restore()

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

    // ---- Vignette ----
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

    // ---- Paper grain generation ----
    private fun generatePaperGrainBitmap(): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val rng = Random(67890)
        for (i in pixels.indices) {
            val base = 240 + rng.nextInt(12)   // lighter base for warm paper
            val noise = rng.nextInt(6) - 3
            val gray = (base + noise).coerceIn(0, 255)
            pixels[i] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)

        // Soft blur
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
        paperBackgroundBitmap?.recycle()
        paperBackgroundBitmap = null
        paperGrainBitmap?.recycle()
        paperGrainBitmap = null
        lineRngSeeds.clear()
    }
}
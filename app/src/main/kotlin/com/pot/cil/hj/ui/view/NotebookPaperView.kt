package com.pot.cil.hj.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.pot.cil.hj.ui.theme.NotebookColors
import kotlin.math.roundToInt

/**
 * Renders real-scale physical notebook paper.
 * 
 * Dimensions based on standard A5 college-ruled notebook:
 * - Page: 148mm × 210mm (A5)
 * - Line spacing: 7.1mm (college rule)
 * - Margin: 32mm from left edge
 * - Holes: 6mm diameter, 12mm from left edge, 80mm apart
 * 
 * At 160dpi baseline: 1mm ≈ 6.3px
 */
class NotebookPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        // ── REAL PHYSICAL DIMENSIONS (mm) ─────────────────────
        const val MM_TO_PX = 6.3f              // At 160dpi baseline
        
        // Page size (A5)
        const val PAGE_WIDTH_MM = 148f
        const val PAGE_HEIGHT_MM = 210f
        
        // Line spacing (college rule = 7.1mm)
        const val LINE_SPACING_MM = 7.1f
        
        // Margin (red line)
        const val MARGIN_LEFT_MM = 32f
        
        // Holes
        const val HOLE_RADIUS_MM = 3f          // 6mm diameter
        const val HOLE_MARGIN_LEFT_MM = 12f      // From left edge
        const val HOLE_SPACING_MM = 80f        // Center-to-center
        
        // Layout
        const val TOP_PADDING_MM = 15f         // Space before first line
        const val BOTTOM_PADDING_MM = 15f
        
        // ── CONVERTED TO PIXELS ────────────────────────────────
        val LINE_SPACING = (LINE_SPACING_MM * MM_TO_PX)
        val MARGIN_LEFT = (MARGIN_LEFT_MM * MM_TO_PX)
        val HOLE_RADIUS = (HOLE_RADIUS_MM * MM_TO_PX)
        val HOLE_MARGIN_LEFT = (HOLE_MARGIN_LEFT_MM * MM_TO_PX)
        val HOLE_SPACING = (HOLE_SPACING_MM * MM_TO_PX)
        val TOP_PADDING = (TOP_PADDING_MM * MM_TO_PX)
        val BOTTOM_PADDING = (BOTTOM_PADDING_MM * MM_TO_PX)
        
        // Total lines that fit on A5
        val LINE_COUNT = ((PAGE_HEIGHT_MM - TOP_PADDING_MM - BOTTOM_PADDING_MM) / LINE_SPACING_MM).toInt()
    }

    // Page dimensions in pixels
    val pageWidth = (PAGE_WIDTH_MM * MM_TO_PX)
    val pageHeight = (PAGE_HEIGHT_MM * MM_TO_PX)

    // ── Paints ───────────────────────────────────────────────
    private val paperPaint = Paint().apply {
        color = NotebookColors.PaperBackground
        isAntiAlias = true
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NotebookColors.LineBlue
        strokeWidth = 1.5f
        alpha = 140
    }

    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NotebookColors.MarginRed
        strokeWidth = 1.8f
        alpha = 180
    }

    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NotebookColors.HoleGray
        style = Paint.Style.FILL
        alpha = 200
    }

    private val holeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#15000000")
        style = Paint.Style.FILL
    }

    private val activeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NotebookColors.ActiveLineHighlight
        style = Paint.Style.FILL
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NotebookColors.SelectionHighlight
        style = Paint.Style.FILL
        alpha = 200
    }

    // Subtle paper texture
    private val texturePaint = Paint().apply {
        color = Color.parseColor("#F8F6F0")
        alpha = 30
    }

    // ── State ────────────────────────────────────────────────
    var activeLineIndex: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    var selectedLineIndices: Set<Int> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    // ── Drawing ──────────────────────────────────────────────
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force exact A5 dimensions
        val widthPx = (pageWidth * resources.displayMetrics.density / 1.0f).roundToInt()
        val heightPx = (pageHeight * resources.displayMetrics.density / 1.0f).roundToInt()
        setMeasuredDimension(widthPx, heightPx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // 1. Paper background with subtle texture
        drawPaperBackground(canvas, width, height)

        // 2. Highlight selected/active lines
        drawLineHighlights(canvas)

        // 3. Horizontal ruled lines (college rule)
        drawRuledLines(canvas, width)

        // 4. Vertical red margin line
        canvas.drawLine(MARGIN_LEFT, 0f, MARGIN_LEFT, height, marginPaint)

        // 5. Notebook holes (3 holes for A5/Letter)
        drawHoles(canvas)
    }

    private fun drawPaperBackground(canvas: Canvas, width: Float, height: Float) {
        // Main paper color
        canvas.drawRect(0f, 0f, width, height, paperPaint)
        
        // Subtle grain texture (horizontal streaks)
        val grainPaint = Paint().apply {
            color = Color.parseColor("#F0EDE5")
            strokeWidth = 0.5f
            alpha = 40
        }
        for (i in 0..(height / 2).toInt()) {
            val y = i * 2f + (i * 137 % 7)  // Pseudo-random
            canvas.drawLine(0f, y, width, y, grainPaint)
        }

        // Page shadow on right edge
        val shadowPaint = Paint().apply {
            shader = android.graphics.LinearGradient(
                width - 8f, 0f, width, 0f,
                Color.parseColor("#08000000"), Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(width - 8f, 0f, width, height, shadowPaint)
    }

    private fun drawLineHighlights(canvas: Canvas) {
        selectedLineIndices.forEach { index ->
            val y = getLineY(index)
            canvas.drawRect(
                MARGIN_LEFT + 3f,
                y - LINE_SPACING * 0.42f,
                width.toFloat() - 10f,
                y + LINE_SPACING * 0.42f,
                selectionPaint
            )
        }

        if (activeLineIndex >= 0 && activeLineIndex !in selectedLineIndices) {
            val y = getLineY(activeLineIndex)
            canvas.drawRect(
                MARGIN_LEFT + 3f,
                y - LINE_SPACING * 0.42f,
                width.toFloat() - 10f,
                y + LINE_SPACING * 0.42f,
                activeLinePaint
            )
        }
    }

    private fun drawRuledLines(canvas: Canvas, width: Float) {
        for (i in 0 until LINE_COUNT) {
            val y = getLineY(i)
            // Slight wobble for hand-drawn feel
            val wobble = (i * 7 % 3 - 1) * 0.3f
            canvas.drawLine(0f, y + wobble, width, y + wobble, linePaint)
        }
    }

    private fun drawHoles(canvas: Canvas) {
        // Standard 3-hole punch for A5/Letter
        val holePositions = listOf(
            HOLE_MARGIN_LEFT + HOLE_SPACING,           // Top
            HOLE_MARGIN_LEFT + HOLE_SPACING * 2.625f,   // Middle (center of page)
            HOLE_MARGIN_LEFT + HOLE_SPACING * 4.25f     // Bottom
        )

        holePositions.forEach { holeY ->
            if (holeY < height - HOLE_MARGIN_LEFT) {
                // Shadow
                canvas.drawCircle(
                    HOLE_MARGIN_LEFT + 0.8f,
                    holeY + 0.8f,
                    HOLE_RADIUS,
                    holeShadowPaint
                )
                // Hole
                canvas.drawCircle(
                    HOLE_MARGIN_LEFT,
                    holeY,
                    HOLE_RADIUS,
                    holePaint
                )
            }
        }
    }

    // ── Public API ───────────────────────────────────────────
    fun getLineY(lineIndex: Int): Float {
        return TOP_PADDING + (lineIndex * LINE_SPACING) + (LINE_SPACING * 0.72f)
    }

    fun getLineIndexFromY(y: Float): Int {
        val relativeY = y - TOP_PADDING
        val index = (relativeY / LINE_SPACING).toInt()
        return index.coerceIn(0, LINE_COUNT - 1)
    }

    fun getTextBaseline(lineIndex: Int): Float {
        return getLineY(lineIndex) + 2f
    }
}

package com.pot.cil.hj.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.pot.cil.hj.ui.theme.NotebookColors

/**
 * Renders the physical notebook paper background with ruled lines,
 * margin, and holes. This is a pure Canvas drawing view.
 */
class NotebookPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Configuration ─────────────────────────────────────────
    companion object {
        const val LINE_SPACING = 90f           // Distance between horizontal lines
        const val MARGIN_LEFT = 160f           // Red margin line position
        const val HOLE_RADIUS = 16f
        const val HOLE_MARGIN_LEFT = 65f       // Holes from left edge
        const val HOLE_SPACING = 240f
        const val TOP_PADDING = 60f            // Space before first line
        const val BOTTOM_PADDING = 80f
        const val LINE_COUNT = 80              // Total lines per page
    }

    // Page dimensions in "paper units"
    val pageWidth = 850f
    val pageHeight get() = TOP_PADDING + (LINE_COUNT * LINE_SPACING) + BOTTOM_PADDING

    // ── Paints ───────────────────────────────────────────────
    private val paperPaint = Paint().apply {
        color = NotebookColors.PaperBackground
        isAntiAlias = true
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NotebookColors.LineBlue
        strokeWidth = 2f
        alpha = 180
    }

    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NotebookColors.MarginRed
        strokeWidth = 2.5f
        alpha = 200
    }

    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NotebookColors.HoleGray
        style = Paint.Style.FILL
        alpha = 160
    }

    private val holeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
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
        val desiredHeight = (pageHeight * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize((pageWidth * resources.displayMetrics.density).toInt(), widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // 1. Paper background with subtle shadow
        drawPaperBackground(canvas, width, height)

        // 2. Highlight selected/active lines
        drawLineHighlights(canvas)

        // 3. Horizontal ruled lines
        drawRuledLines(canvas, width)

        // 4. Vertical red margin
        canvas.drawLine(MARGIN_LEFT, 0f, MARGIN_LEFT, height, marginPaint)

        // 5. Notebook holes
        drawHoles(canvas)
    }

    private fun drawPaperBackground(canvas: Canvas, width: Float, height: Float) {
        // Main paper
        canvas.drawRect(0f, 0f, width, height, paperPaint)
        
        // Subtle page shadow on right edge
        val shadowPaint = Paint().apply {
            shader = android.graphics.LinearGradient(
                width - 20f, 0f, width, 0f,
                Color.parseColor("#10000000"), Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(width - 20f, 0f, width, height, shadowPaint)
    }

    private fun drawLineHighlights(canvas: Canvas) {
        selectedLineIndices.forEach { index ->
            val y = getLineY(index)
            canvas.drawRect(
                MARGIN_LEFT + 5f,
                y - LINE_SPACING * 0.45f,
                width.toFloat() - 20f,
                y + LINE_SPACING * 0.45f,
                selectionPaint
            )
        }

        if (activeLineIndex >= 0 && activeLineIndex !in selectedLineIndices) {
            val y = getLineY(activeLineIndex)
            canvas.drawRect(
                MARGIN_LEFT + 5f,
                y - LINE_SPACING * 0.45f,
                width.toFloat() - 20f,
                y + LINE_SPACING * 0.45f,
                activeLinePaint
            )
        }
    }

    private fun drawRuledLines(canvas: Canvas, width: Float) {
        for (i in 0 until LINE_COUNT) {
            val y = getLineY(i)
            canvas.drawLine(0f, y, width, y, linePaint)
        }
    }

    private fun drawHoles(canvas: Canvas) {
        var holeY = HOLE_SPACING
        while (holeY < height) {
            // Shadow
            canvas.drawCircle(HOLE_MARGIN_LEFT + 2f, holeY + 2f, HOLE_RADIUS, holeShadowPaint)
            // Hole
            canvas.drawCircle(HOLE_MARGIN_LEFT, holeY, HOLE_RADIUS, holePaint)
            holeY += HOLE_SPACING
        }
    }

    // ── Public API ───────────────────────────────────────────
    fun getLineY(lineIndex: Int): Float {
        return TOP_PADDING + (lineIndex * LINE_SPACING) + (LINE_SPACING / 2)
    }

    fun getLineIndexFromY(y: Float): Int {
        val relativeY = y - TOP_PADDING
        val index = (relativeY / LINE_SPACING).toInt()
        return index.coerceIn(0, LINE_COUNT - 1)
    }

    fun getTextBaseline(lineIndex: Int): Float {
        return getLineY(lineIndex) + 8f  // Slight offset for text baseline
    }
}

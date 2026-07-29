package com.pot.cil.hj

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class NotebookPaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Colors
    private val paperColor = Color.parseColor("#FEFCF3")      // Slightly warm white
    private val lineColor = Color.parseColor("#A4C2F4")       // Light blue lines
    private val marginColor = Color.parseColor("#E06666")     // Red margin line
    private val holeColor = Color.parseColor("#555555")       // Dark gray holes

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 2f
    }

    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = marginColor
        strokeWidth = 2.5f
    }

    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = holeColor
        style = Paint.Style.FILL
    }

    private val paperPaint = Paint().apply {
        color = paperColor
    }

    // Notebook settings (college ruled)
    private val lineSpacing = 80f      // Distance between horizontal lines
    private val marginLeft = 140f      // Red margin line from left
    private val holeRadius = 18f
    private val holeMarginLeft = 60f   // Holes from left edge
    private val holeSpacing = 220f     // Distance between holes

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // 1. Draw paper background
        canvas.drawRect(0f, 0f, width, height, paperPaint)

        // 2. Draw horizontal blue lines (college ruled)
        var y = lineSpacing
        while (y < height) {
            canvas.drawLine(0f, y, width, y, linePaint)
            y += lineSpacing
        }

        // 3. Draw vertical red margin line
        canvas.drawLine(marginLeft, 0f, marginLeft, height, marginPaint)

        // 4. Draw notebook holes (left side)
        var holeY = holeSpacing
        while (holeY < height) {
            canvas.drawCircle(holeMarginLeft, holeY, holeRadius, holePaint)
            holeY += holeSpacing
        }
    }
}
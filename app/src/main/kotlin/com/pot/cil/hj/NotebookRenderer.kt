package com.pot.cil.hj

import android.content.Context
import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.min
import kotlin.random.Random

class NotebookRenderer(private val context: Context) {

    // ---- Paper specs ----
    private val LINE_SPACING_MM = 7.1f
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

    // ---- RenderNode caching ----
    private val staticRenderNode = RenderNode("NotebookStatic")
    private val dynamicRenderNode = RenderNode("NotebookDynamic")

    // ---- Shaders & Effects ----
    private lateinit var grainShader: RuntimeShader

    // ---- Paints ----
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 153, 191)
        strokeWidth = 1.5f
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(191, 51, 51)
        strokeWidth = 2f
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(77, 102, 153, 255)
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

    // ---- Flags to know if nodes have been recorded ----
    private var staticNodeReady = false
    private var dynamicNodeReady = false

    init {
        initShader()
    }

    // ---- AGSL Shader Source (Paper Grain) ----
    private val GRAIN_SHADER_SOURCE = """
        uniform float2 uResolution;
        uniform float uTime;
        uniform float uIntensity;

        half4 main(vec2 fragCoord) {
            vec2 uv = fragCoord / uResolution;
            // Simple noise for paper grain
            float grain = 0.0;
            for (int i = 0; i < 3; i++) {
                vec2 p = uv * (10.0 + float(i) * 5.0) + uTime * 0.01;
                grain += fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
            }
            grain *= uIntensity;
            half3 color = half3(0.98, 0.96, 0.90) + grain * 0.02;
            return half4(color, 1.0);
        }
    """.trimIndent()

    private fun initShader() {
        grainShader = RuntimeShader(GRAIN_SHADER_SOURCE)
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
        recalcPaperParams()
        rebuildStaticNode()
        rebuildDynamicNode()
        // Update shader uniforms
        grainShader.setFloatUniform("uResolution", width.toFloat(), height.toFloat())
    }

    // ---- Drawing ----
    fun draw(canvas: Canvas, width: Int, height: Int) {
        // Apply the current transform to the canvas
        canvas.save()
        canvas.concat(transformMatrix)

        // Draw the static RenderNode (paper background, grain, lines, margin)
        if (staticNodeReady) {
            staticRenderNode.drawInto(canvas)
        }

        // Draw the dynamic RenderNode (highlight, text)
        if (dynamicNodeReady) {
            dynamicRenderNode.drawInto(canvas)
        }

        canvas.restore()

        // ---- Vignette (screen space, no transform) ----
        drawVignette(canvas, width, height)
    }

    // ---- Private helpers ----

    private fun recalcPaperParams() {
        val dpi = context.resources.displayMetrics.densityDpi.toFloat()
        val pxPerMm = dpi / 25.4f

        val calcSpacing = LINE_SPACING_MM * pxPerMm
        val viewBasedSpacing = (viewHeight - (TOP_MARGIN_MM + BOTTOM_MARGIN_MM) * pxPerMm) / TOTAL_LINES
        lineSpacingPx = min(calcSpacing, viewBasedSpacing)
        topMarginPx = TOP_MARGIN_MM * pxPerMm
        leftMarginPx = (LEFT_MARGIN_MM * pxPerMm).coerceAtMost(viewWidth * 0.3f)
        bottomMarginPx = BOTTOM_MARGIN_MM * pxPerMm

        val availableHeight = viewHeight - topMarginPx - bottomMarginPx
        totalLinesValue = (availableHeight / lineSpacingPx).toInt().coerceAtMost(TOTAL_LINES)

        textPaint.textSize = lineSpacingPx * 0.5f

        // Clamp selection and remove out-of-bounds text
        selectedLine = selectedLine.coerceIn(0, totalLinesValue - 1)
        textPerLine.keys.removeAll { it !in 0 until totalLinesValue }
    }

    private fun rebuildStaticNode() {
        val canvas = staticRenderNode.beginRecording(viewWidth, viewHeight)
        try {
            // ---- Paper background color ----
            canvas.drawColor(Color.rgb(250, 245, 230))

            // ---- Paper grain (via RuntimeShader on a Paint) ----
            grainShader.setFloatUniform("uTime", System.currentTimeMillis() % 10000 / 10000f)
            grainShader.setFloatUniform("uIntensity", 1.0f)
            val grainPaint = Paint().apply {
                shader = grainShader
            }
            canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), grainPaint)

            // ---- Ruled lines ----
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
        } finally {
            staticRenderNode.endRecording()
        }
    }

    private fun rebuildDynamicNode() {
        val canvas = dynamicRenderNode.beginRecording(viewWidth, viewHeight)
        try {
            // Clear (transparent background)
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

            // ---- Text (with humanization) ----
            for ((line, text) in textPerLine) {
                if (line !in 0 until totalLinesValue) continue
                drawHumanizedText(canvas, text, line)
            }

            dynamicNodeReady = true
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
            val jitterY = (rng.nextFloat() * 2f - 1f) * maxJitterY * 0.6f // humanize factor 0.6

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
        // Simple clamping – can be improved later
        val values = FloatArray(9)
        transformMatrix.getValues(values)
        // For now, we just keep the translation within reasonable bounds
        // More advanced clamping will be added later.
    }
}
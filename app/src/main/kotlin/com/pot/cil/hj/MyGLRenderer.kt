package com.pot.cil.hj

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MyGLRenderer : GLSurfaceView.Renderer {

    // ---- Paper Dimensions (in pixels at ~96 DPI) ----
    // 8.5" × 11" ≈ 816 × 1056 pixels at 96 DPI
    // But we scale to screen resolution

    // ---- Constants based on real notebook specs ----
    private val LINE_SPACING_MM = 7.1f      // 7.1mm college ruled
    private val TOP_MARGIN_MM = 32f         // 32mm top margin
    private val LEFT_MARGIN_MM = 32f        // 32mm left margin (red line)
    private val BOTTOM_MARGIN_MM = 12.7f    // ~0.5" bottom margin
    private val TOTAL_LINES = 32            // 32-33 lines per page

    // ---- Runtime values (calculated from screen DPI) ----
    private var lineSpacingPx = 30f         // Will be calculated
    private var topMarginPx = 40f           // Will be calculated
    private var leftMarginPx = 40f          // Will be calculated
    private var bottomMarginPx = 16f        // Will be calculated
    private var totalLines = TOTAL_LINES

    // ---- OpenGL resources ----
    private val quadVertices = floatArrayOf(
        -1.0f,  1.0f,  0.0f, 0.0f,
        -1.0f, -1.0f,  0.0f, 1.0f,
         1.0f,  1.0f,  1.0f, 0.0f,
         1.0f, -1.0f,  1.0f, 1.0f
    )
    private lateinit var vertexBuffer: FloatBuffer
    private var programId = 0
    private var resolutionUniform = -1
    private var textTextureUniform = -1
    private var selectedLineUniform = -1
    private var lineSpacingUniform = -1
    private var topMarginUniform = -1
    private var leftMarginUniform = -1
    private var bottomMarginUniform = -1
    private var totalLinesUniform = -1

    private var textTextureId = 0
    private var viewWidth = 0
    private var viewHeight = 0
    private var selectedLine = 3  // Default selected line (0-indexed)

    // ---- Vertex Shader ----
    private val vertexShaderCode = """
        #version 320 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        uniform vec2 uResolution;
        out vec2 vPixelCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
            vPixelCoord = aTexCoord * uResolution;
        }
    """.trimIndent()

    // ---- Fragment Shader with Line Selection ----
    private val fragmentShaderCode = """
        #version 320 es
        precision mediump float;

        in vec2 vPixelCoord;
        in vec2 vTexCoord;
        out vec4 outColor;

        uniform sampler2D uTextTexture;
        uniform float uLineSpacing;
        uniform float uTopMargin;
        uniform float uLeftMargin;
        uniform float uBottomMargin;
        uniform float uTotalLines;
        uniform float uSelectedLine;

        // Paper colors
        uniform vec3 uPaperColor;
        uniform vec3 uLineColor;
        uniform vec3 uMarginColor;
        uniform vec3 uSelectedLineColor;
        uniform vec3 uAgedColor;
        uniform float uVignetteStrength;

        void main() {
            vec3 finalColor = uPaperColor;
            float y = vPixelCoord.y;

            // ---- Check if we're within the writing area ----
            float inWritingArea = step(uTopMargin, y) * step(y, vPixelCoord.y - uBottomMargin);

            // ---- Horizontal Lines ----
            float relativeY = y - uTopMargin;
            float gridY = mod(relativeY, uLineSpacing);
            float distToLine = abs(gridY - uLineSpacing * 0.5);
            float lineFactor = 1.0 - smoothstep(0.0, 1.5, distToLine);
            lineFactor *= inWritingArea;
            finalColor = mix(finalColor, uLineColor, lineFactor);

            // ---- Selected Line Highlight ----
            float selectedLineY = uTopMargin + uSelectedLine * uLineSpacing + uLineSpacing * 0.5;
            float distToSelected = abs(y - selectedLineY);
            float selectedFactor = 1.0 - smoothstep(0.0, uLineSpacing * 0.4, distToSelected);
            selectedFactor *= inWritingArea;
            // Only show highlight if a line is selected (uSelectedLine >= 0)
            float hasSelection = step(0.0, uSelectedLine);
            selectedFactor *= hasSelection;
            finalColor = mix(finalColor, uSelectedLineColor, selectedFactor * 0.3);

            // ---- Left Margin (Red Vertical Line) ----
            float marginActive = step(600.0, vPixelCoord.x);
            float distToMargin = abs(vPixelCoord.x - uLeftMargin);
            float marginFactor = 1.0 - smoothstep(0.0, 1.5, distToMargin);
            marginFactor *= marginActive;
            marginFactor *= inWritingArea;
            finalColor = mix(finalColor, uMarginColor, marginFactor);

            // ---- Aging (Vignette) ----
            float ageVignette = 1.0 - length(vTexCoord - 0.5) * uVignetteStrength;
            finalColor = mix(finalColor, uAgedColor, (1.0 - ageVignette) * 0.15);

            // ---- Text Overlay ----
            vec4 textColor = texture(uTextTexture, vTexCoord);
            finalColor = mix(finalColor, textColor.rgb, textColor.a);

            finalColor = clamp(finalColor, 0.0, 1.0);
            outColor = vec4(finalColor, 1.0);
        }
    """.trimIndent()

    // ---- Lifecycle Methods ----

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Calculate pixel values based on screen density
        // We use the view dimensions to calculate proper spacing
        // At ~96 DPI, 7.1mm ≈ 27 pixels
        // We'll calculate dynamically in onSurfaceChanged

        val vertexShader = loadShader(GLES32.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES32.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES32.glCreateProgram().also {
            GLES32.glAttachShader(it, vertexShader)
            GLES32.glAttachShader(it, fragmentShader)
            GLES32.glLinkProgram(it)
        }

        resolutionUniform = GLES32.glGetUniformLocation(programId, "uResolution")
        textTextureUniform = GLES32.glGetUniformLocation(programId, "uTextTexture")
        selectedLineUniform = GLES32.glGetUniformLocation(programId, "uSelectedLine")
        lineSpacingUniform = GLES32.glGetUniformLocation(programId, "uLineSpacing")
        topMarginUniform = GLES32.glGetUniformLocation(programId, "uTopMargin")
        leftMarginUniform = GLES32.glGetUniformLocation(programId, "uLeftMargin")
        bottomMarginUniform = GLES32.glGetUniformLocation(programId, "uBottomMargin")
        totalLinesUniform = GLES32.glGetUniformLocation(programId, "uTotalLines")

        // Set constant uniforms
        GLES32.glUseProgram(programId)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(programId, "uPaperColor"), 0.98f, 0.96f, 0.90f)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(programId, "uLineColor"), 0.55f, 0.60f, 0.75f)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(programId, "uMarginColor"), 0.75f, 0.20f, 0.20f)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(programId, "uSelectedLineColor"), 0.4f, 0.6f, 1.0f)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(programId, "uAgedColor"), 0.92f, 0.88f, 0.82f)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(programId, "uVignetteStrength"), 0.3f)

        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices).position(0) }

        textTextureId = generateTextTexture()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES32.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height

        // Calculate pixel values based on screen dimensions
        // We want 32 lines with 7.1mm spacing
        // The writing area is from top margin to bottom margin
        // Total writing height = viewHeight - topMargin - bottomMargin
        // lineSpacing = writingHeight / totalLines

        // At 96 DPI: 7.1mm ≈ 27 pixels
        // At higher DPI, scale proportionally
        val dpi = context.resources.displayMetrics.densityDpi.toFloat()
        val pixelsPerMm = dpi / 25.4f

        // Calculate from real-world dimensions
        val lineSpacingMm = 7.1f
        val topMarginMm = 32f
        val leftMarginMm = 32f
        val bottomMarginMm = 12.7f

        // Use the smaller of calculated vs view-based to ensure all lines fit
        val calculatedLineSpacing = lineSpacingMm * pixelsPerMm
        val viewBasedLineSpacing = (height - (topMarginMm + bottomMarginMm) * pixelsPerMm) / totalLines

        // Use the smaller value to ensure all lines fit
        lineSpacingPx = minOf(calculatedLineSpacing, viewBasedLineSpacing)
        topMarginPx = topMarginMm * pixelsPerMm
        leftMarginPx = leftMarginMm * pixelsPerMm
        bottomMarginPx = bottomMarginMm * pixelsPerMm

        // Recalculate total lines based on available space
        val availableHeight = height - topMarginPx - bottomMarginPx
        totalLines = (availableHeight / lineSpacingPx).toInt().coerceAtMost(TOTAL_LINES)

        GLES32.glUseProgram(programId)
        GLES32.glUniform2f(resolutionUniform, width.toFloat(), height.toFloat())
        GLES32.glUniform1f(lineSpacingUniform, lineSpacingPx)
        GLES32.glUniform1f(topMarginUniform, topMarginPx)
        GLES32.glUniform1f(leftMarginUniform, leftMarginPx)
        GLES32.glUniform1f(bottomMarginUniform, bottomMarginPx)
        GLES32.glUniform1f(totalLinesUniform, totalLines.toFloat())
        GLES32.glUniform1f(selectedLineUniform, selectedLine.toFloat())
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)

        GLES32.glUseProgram(programId)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textTextureId)
        GLES32.glUniform1i(textTextureUniform, 0)

        GLES32.glEnableVertexAttribArray(0)
        GLES32.glEnableVertexAttribArray(1)
        vertexBuffer.position(0)
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 16, vertexBuffer)

        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)

        GLES32.glDisableVertexAttribArray(0)
        GLES32.glDisableVertexAttribArray(1)
    }

    // ---- Public API ----

    fun setTextOverlay(textOverlay: TextOverlay, lineNumber: Int) {
        if (viewWidth == 0 || viewHeight == 0) return

        // Ensure line is within bounds
        val safeLine = lineNumber.coerceIn(0, totalLines - 1)

        val bitmap = renderTextToBitmap(textOverlay, viewWidth, viewHeight, safeLine)
        uploadTexture(bitmap)

        // Update selected line
        setSelectedLine(safeLine)
    }

    fun clearTextOverlay() {
        val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        uploadTexture(bitmap)
    }

    fun setSelectedLine(lineNumber: Int) {
        selectedLine = lineNumber.coerceIn(0, totalLines - 1)
        GLES32.glUseProgram(programId)
        GLES32.glUniform1f(selectedLineUniform, selectedLine.toFloat())
    }

    fun getTotalLines(): Int = totalLines
    fun getLineHeightPixels(): Float = lineSpacingPx
    fun getTopMarginPixels(): Float = topMarginPx

    // ---- Private Helpers ----

    private fun renderTextToBitmap(textOverlay: TextOverlay, width: Int, height: Int, lineNumber: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val paint = Paint().apply {
            color = textOverlay.color
            textSize = textOverlay.textSize
            isAntiAlias = true
            isSubpixelText = true
            textAlign = Paint.Align.LEFT
        }

        // Position text on the selected line
        val y = topMarginPx + (lineNumber * lineSpacingPx) + textOverlay.yOffset
        canvas.drawText(textOverlay.text, textOverlay.xOffset, y, paint)
        return bitmap
    }

    private fun uploadTexture(bitmap: Bitmap) {
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textTextureId)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR_MIPMAP_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES32.glGenerateMipmap(GLES32.GL_TEXTURE_2D)
        bitmap.recycle()
    }

    private fun generateTextTexture(): Int {
        val textures = IntArray(1)
        GLES32.glGenTextures(1, textures, 0)
        val id = textures[0]
        val tempBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        tempBitmap.eraseColor(Color.TRANSPARENT)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, id)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, tempBitmap, 0)
        tempBitmap.recycle()
        return id
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES32.glCreateShader(type).also { shader ->
            GLES32.glShaderSource(shader, shaderCode)
            GLES32.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val info = GLES32.glGetShaderInfoLog(shader)
                throw RuntimeException("Shader compilation failed: $info")
            }
        }
    }

    // Keep a reference to context for DPI calculations
    private val context: android.content.Context by lazy {
        // This will be set from the surface view
        throw IllegalStateException("Context not available")
    }

    // Set context from surface view
    fun setContext(ctx: android.content.Context) {
        // We'll use a different approach - pass context via constructor
    }
}
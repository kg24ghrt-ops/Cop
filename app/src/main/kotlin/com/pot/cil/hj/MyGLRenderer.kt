package com.pot.cil.hj

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.random.Random

class MyGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // ---- Paper specifications ----
    private val LINE_SPACING_MM = 7.1f
    private val TOP_MARGIN_MM = 32f
    private val LEFT_MARGIN_MM = 32f
    private val BOTTOM_MARGIN_MM = 12.7f
    private val TOTAL_LINES = 32

    // ---- Runtime pixel values ----
    private var lineSpacingPx = 30f
    private var topMarginPx = 40f
    private var leftMarginPx = 40f
    private var bottomMarginPx = 16f
    private var totalLines = TOTAL_LINES
    private var textAreaWidth = 0
    private var atlasHeight = 0

    // ---- Text storage ----
    private val textPerLine = mutableMapOf<Int, String>()

    // ---- OpenGL resources ----
    private val quadVertices = floatArrayOf(
        -1.0f,  1.0f,  0.0f, 0.0f,
        -1.0f, -1.0f,  0.0f, 1.0f,
         1.0f,  1.0f,  1.0f, 0.0f,
         1.0f, -1.0f,  1.0f, 1.0f
    )
    private lateinit var vertexBuffer: FloatBuffer
    private var vao = 0
    private var programId = 0

    // Uniform locations (validated and cached)
    private var resolutionUniform = -1
    private var textTextureUniform = -1
    private var noiseTextureUniform = -1
    private var selectedLineUniform = -1
    private var lineSpacingUniform = -1
    private var topMarginUniform = -1
    private var leftMarginUniform = -1
    private var bottomMarginUniform = -1
    private var totalLinesUniform = -1
    private var textAreaWidthUniform = -1
    private var atlasHeightUniform = -1
    private var noiseScaleUniform = -1
    private var noiseStrengthUniform = -1
    private var paperColorUniform = -1
    private var lineColorUniform = -1
    private var marginColorUniform = -1
    private var selectedLineColorUniform = -1
    private var agedColorUniform = -1
    private var vignetteStrengthUniform = -1

    // Textures
    private var textAtlasTextureId = 0
    private var noiseTextureId = 0

    // State
    private var viewWidth = 0
    private var viewHeight = 0
    private var selectedLine = 3

    // Text paint (reusable)
    private val textPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        isSubpixelText = true
        textAlign = Paint.Align.LEFT
    }

    // ---- Shaders ----
    private val vertexShaderCode = """
        #version 320 es
        precision mediump float;
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

    private val fragmentShaderCode = """
        #version 320 es
        precision mediump float;

        in vec2 vPixelCoord;
        in vec2 vTexCoord;
        out vec4 outColor;

        uniform sampler2D uTextTexture;
        uniform sampler2D uNoiseTexture;
        uniform vec2 uResolution;
        uniform float uLineSpacing;
        uniform float uTopMargin;
        uniform float uLeftMargin;
        uniform float uBottomMargin;
        uniform float uTotalLines;
        uniform float uSelectedLine;
        uniform float uTextAreaWidth;
        uniform float uAtlasHeight;
        uniform float uNoiseScale;
        uniform float uNoiseStrength;

        uniform vec3 uPaperColor;
        uniform vec3 uLineColor;
        uniform vec3 uMarginColor;
        uniform vec3 uSelectedLineColor;
        uniform vec3 uAgedColor;
        uniform float uVignetteStrength;

        void main() {
            float y = vPixelCoord.y;
            vec3 finalColor = uPaperColor;

            // Writing area mask
            float inWritingArea = step(uTopMargin, y) *
                                  step(y, uResolution.y - uBottomMargin);

            // Horizontal lines
            float relativeY = y - uTopMargin;
            float gridY = mod(relativeY, uLineSpacing);
            float distToLine = abs(gridY - uLineSpacing * 0.5);
            float lineFactor = 1.0 - smoothstep(0.0, 1.5, distToLine);
            lineFactor *= inWritingArea;
            finalColor = mix(finalColor, uLineColor, lineFactor);

            // Selected line highlight
            float selectedLineY = uTopMargin + uSelectedLine * uLineSpacing +
                                  uLineSpacing * 0.5;
            float distToSelected = abs(y - selectedLineY);
            float selectedFactor = 1.0 - smoothstep(0.0, uLineSpacing * 0.4,
                                                    distToSelected);
            selectedFactor *= inWritingArea * step(0.0, uSelectedLine);
            finalColor = mix(finalColor, uSelectedLineColor, selectedFactor * 0.3);

            // Vertical red margin
            float distToMargin = abs(vPixelCoord.x - uLeftMargin);
            float marginFactor = 1.0 - smoothstep(0.0, 1.5, distToMargin);
            marginFactor *= inWritingArea;
            finalColor = mix(finalColor, uMarginColor, marginFactor);

            // Paper grain (subtle noise)
            float noise = texture(uNoiseTexture, vPixelCoord * uNoiseScale).r;
            float grain = (noise - 0.5) * uNoiseStrength;
            finalColor = mix(finalColor, finalColor * (1.0 + grain), 0.6);

            // Aging vignette
            float ageVignette = 1.0 - length(vTexCoord - 0.5) * uVignetteStrength;
            finalColor = mix(finalColor, uAgedColor, (1.0 - ageVignette) * 0.15);

            // Text from atlas (already top‑down)
            vec3 textRgb = vec3(0.0);
            float textAlpha = 0.0;
            if (inWritingArea > 0.5) {
                float relX = vPixelCoord.x - uLeftMargin;
                float relY = vPixelCoord.y - uTopMargin;
                if (relX >= 0.0 && relX < uTextAreaWidth &&
                    relY < uTotalLines * uLineSpacing) {
                    float lineIdx = floor(relY / uLineSpacing);
                    float yInLine = mod(relY, uLineSpacing);
                    vec2 atlasUV = vec2(relX / uTextAreaWidth,
                                        (lineIdx * uLineSpacing + yInLine) / uAtlasHeight);
                    vec4 texel = texture(uTextTexture, atlasUV);
                    textRgb = texel.rgb;
                    textAlpha = texel.a;
                }
            }
            finalColor = mix(finalColor, textRgb, textAlpha);

            finalColor = clamp(finalColor, 0.0, 1.0);
            outColor = vec4(finalColor, 1.0);
        }
    """.trimIndent()

    // ---- Lifecycle ----

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)   // dark gray background

        val vertexShader = loadShader(GLES32.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES32.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES32.glCreateProgram().also {
            GLES32.glAttachShader(it, vertexShader)
            GLES32.glAttachShader(it, fragmentShader)
            GLES32.glLinkProgram(it)
            val linked = IntArray(1)
            GLES32.glGetProgramiv(it, GLES32.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                val info = GLES32.glGetProgramInfoLog(it)
                Log.e("MyGLRenderer", "Program linking failed: $info")
                throw RuntimeException("Program linking failed: $info")
            }
        }

        // Cache all uniform locations and verify they are valid
        resolutionUniform = requireUniform("uResolution")
        textTextureUniform = requireUniform("uTextTexture")
        noiseTextureUniform = requireUniform("uNoiseTexture")
        selectedLineUniform = requireUniform("uSelectedLine")
        lineSpacingUniform = requireUniform("uLineSpacing")
        topMarginUniform = requireUniform("uTopMargin")
        leftMarginUniform = requireUniform("uLeftMargin")
        bottomMarginUniform = requireUniform("uBottomMargin")
        totalLinesUniform = requireUniform("uTotalLines")
        textAreaWidthUniform = requireUniform("uTextAreaWidth")
        atlasHeightUniform = requireUniform("uAtlasHeight")
        noiseScaleUniform = requireUniform("uNoiseScale")
        noiseStrengthUniform = requireUniform("uNoiseStrength")
        paperColorUniform = requireUniform("uPaperColor")
        lineColorUniform = requireUniform("uLineColor")
        marginColorUniform = requireUniform("uMarginColor")
        selectedLineColorUniform = requireUniform("uSelectedLineColor")
        agedColorUniform = requireUniform("uAgedColor")
        vignetteStrengthUniform = requireUniform("uVignetteStrength")

        // Set static colour uniforms once
        GLES32.glUseProgram(programId)
        setColorsAndConstants()

        // Quad geometry and VAO
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices).position(0) }

        val vaos = IntArray(1)
        GLES32.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]
        GLES32.glBindVertexArray(vao)

        GLES32.glEnableVertexAttribArray(0)
        vertexBuffer.position(0)
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 16, vertexBuffer)

        GLES32.glEnableVertexAttribArray(1)
        vertexBuffer.position(2)
        GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 16, vertexBuffer)

        GLES32.glBindVertexArray(0)

        // Noise texture
        noiseTextureId = generateNoiseTexture()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES32.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height

        val dpi = context.resources.displayMetrics.densityDpi.toFloat()
        val pixelsPerMm = dpi / 25.4f

        val calculatedLineSpacing = LINE_SPACING_MM * pixelsPerMm
        val viewBasedLineSpacing =
            (height - (TOP_MARGIN_MM + BOTTOM_MARGIN_MM) * pixelsPerMm) / TOTAL_LINES
        lineSpacingPx = minOf(calculatedLineSpacing, viewBasedLineSpacing)
        topMarginPx = TOP_MARGIN_MM * pixelsPerMm
        leftMarginPx = (LEFT_MARGIN_MM * pixelsPerMm).coerceAtMost(width * 0.3f)
        bottomMarginPx = BOTTOM_MARGIN_MM * pixelsPerMm

        val availableHeight = height - topMarginPx - bottomMarginPx
        totalLines = (availableHeight / lineSpacingPx).toInt().coerceAtMost(TOTAL_LINES)

        textAreaWidth = maxOf(1, (width - leftMarginPx).toInt())
        atlasHeight = maxOf(1, (totalLines * lineSpacingPx).toInt())

        // (Re)create the atlas texture with a guaranteed complete 1x1 transparent pixel
        if (textAtlasTextureId != 0) {
            GLES32.glDeleteTextures(1, intArrayOf(textAtlasTextureId), 0)
        }
        textAtlasTextureId = createEmptyTexture(textAreaWidth, atlasHeight)

        // Update dynamic uniforms
        GLES32.glUseProgram(programId)
        GLES32.glUniform2f(resolutionUniform, width.toFloat(), height.toFloat())
        GLES32.glUniform1f(lineSpacingUniform, lineSpacingPx)
        GLES32.glUniform1f(topMarginUniform, topMarginPx)
        GLES32.glUniform1f(leftMarginUniform, leftMarginPx)
        GLES32.glUniform1f(bottomMarginUniform, bottomMarginPx)
        GLES32.glUniform1f(totalLinesUniform, totalLines.toFloat())
        GLES32.glUniform1f(textAreaWidthUniform, textAreaWidth.toFloat())
        GLES32.glUniform1f(atlasHeightUniform, atlasHeight.toFloat())
        GLES32.glUniform1f(selectedLineUniform, selectedLine.toFloat())

        // Restore any existing text
        for ((line, text) in textPerLine) {
            updateLineTexture(line, text)
        }
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        GLES32.glUseProgram(programId)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textAtlasTextureId)
        GLES32.glUniform1i(textTextureUniform, 0)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE1)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, noiseTextureId)
        GLES32.glUniform1i(noiseTextureUniform, 1)

        GLES32.glBindVertexArray(vao)
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)
        GLES32.glBindVertexArray(0)
    }

    // ---- Public API (unchanged) ----

    fun setTextOnLine(lineNumber: Int, text: String) {
        val safeLine = lineNumber.coerceIn(0, totalLines - 1)
        if (text.isEmpty()) {
            textPerLine.remove(safeLine)
        } else {
            textPerLine[safeLine] = text
        }
        if (viewWidth > 0) {
            updateLineTexture(safeLine, text)
        }
        setSelectedLine(safeLine)
    }

    fun getTextOnLine(lineNumber: Int): String? = textPerLine[lineNumber]

    fun clearAllText() {
        textPerLine.clear()
        if (viewWidth > 0 && textAtlasTextureId != 0) {
            // Clear atlas by re-allocating a transparent texture
            GLES32.glDeleteTextures(1, intArrayOf(textAtlasTextureId), 0)
            textAtlasTextureId = createEmptyTexture(textAreaWidth, atlasHeight)
        }
    }

    fun setSelectedLine(lineNumber: Int) {
        selectedLine = lineNumber.coerceIn(0, totalLines - 1)
        GLES32.glUseProgram(programId)
        GLES32.glUniform1f(selectedLineUniform, selectedLine.toFloat())
    }

    // ---- Getters ----
    fun getTotalLines(): Int = totalLines
    fun getLineHeightPixels(): Float = lineSpacingPx
    fun getTopMarginPixels(): Float = topMarginPx
    fun getLeftMarginPixels(): Float = leftMarginPx
    fun getLineSpacingPixels(): Float = lineSpacingPx

    // ---- Private helpers ----

    /**
     * Retrieves a uniform location and throws if it's -1 (missing/optimized out).
     */
    private fun requireUniform(name: String): Int {
        val loc = GLES32.glGetUniformLocation(programId, name)
        if (loc == -1) {
            throw RuntimeException("Uniform '$name' not found in shader program")
        }
        return loc
    }

    /**
     * Sets the static colour / vignette / noise uniforms.
     */
    private fun setColorsAndConstants() {
        GLES32.glUniform3f(paperColorUniform, 0.98f, 0.96f, 0.90f)
        GLES32.glUniform3f(lineColorUniform, 0.55f, 0.60f, 0.75f)
        GLES32.glUniform3f(marginColorUniform, 0.75f, 0.20f, 0.20f)
        GLES32.glUniform3f(selectedLineColorUniform, 0.4f, 0.6f, 1.0f)
        GLES32.glUniform3f(agedColorUniform, 0.92f, 0.88f, 0.82f)
        GLES32.glUniform1f(vignetteStrengthUniform, 0.3f)
        GLES32.glUniform1f(noiseScaleUniform, 0.15f)
        GLES32.glUniform1f(noiseStrengthUniform, 0.06f)
    }

    /**
     * Creates a texture of the given size, initialised to fully transparent.
     * Passing null as pixel data is the correct way to allocate without uploading,
     * guaranteeing the texture is complete on all GLES 3.0+ devices.
     */
    private fun createEmptyTexture(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES32.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texId)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)

        // null → driver allocates memory; texture is complete and initially undefined
        GLES32.glTexImage2D(
            GLES32.GL_TEXTURE_2D, 0, GLES32.GL_RGBA,
            width, height, 0,
            GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, null
        )
        checkGlError("createEmptyTexture")
        return texId
    }

    /**
     * Updates a single line strip in the texture atlas.
     */
    private fun updateLineTexture(line: Int, text: String) {
        if (textAtlasTextureId == 0 || textAreaWidth <= 0) return

        val stripHeight = lineSpacingPx.toInt()
        val yOffset = line * stripHeight

        val stripBitmap = Bitmap.createBitmap(
            textAreaWidth, stripHeight, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(stripBitmap)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        if (text.isNotEmpty()) {
            textPaint.textSize = lineSpacingPx * 0.5f
            val fm = textPaint.fontMetrics
            val baselineY = stripHeight * 0.6f + (fm.bottom - fm.top) / 2f - fm.bottom
            canvas.drawText(text, 0f, baselineY, textPaint)
        }

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textAtlasTextureId)
        GLUtils.texSubImage2D(
            GLES32.GL_TEXTURE_2D, 0,
            0, yOffset, stripBitmap
        )
        stripBitmap.recycle()
    }

    private fun generateNoiseTexture(): Int {
        val size = 256
        val noiseBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val rng = Random(12345)
        for (i in pixels.indices) {
            val gray = rng.nextInt(256)
            pixels[i] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        noiseBitmap.setPixels(pixels, 0, size, 0, 0, size, size)

        val texIds = IntArray(1)
        GLES32.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texId)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_REPEAT)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_REPEAT)
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, noiseBitmap, 0)
        noiseBitmap.recycle()
        return texId
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES32.glCreateShader(type)
        GLES32.glShaderSource(shader, shaderCode)
        GLES32.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES32.glGetShaderInfoLog(shader)
            Log.e("MyGLRenderer", "Shader compilation failed: $info")
            throw RuntimeException("Shader compilation failed: $info")
        }
        return shader
    }

    /**
     * Checks for OpenGL errors and logs them. Call after important GL operations.
     */
    private fun checkGlError(op: String) {
        val error = GLES32.glGetError()
        if (error != GLES32.GL_NO_ERROR) {
            Log.e("MyGLRenderer", "OpenGL error after $op: 0x${Integer.toHexString(error)}")
        }
    }
}
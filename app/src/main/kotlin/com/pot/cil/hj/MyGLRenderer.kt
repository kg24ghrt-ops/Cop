package com.pot.cil.hj

import android.graphics.Bitmap
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MyGLRenderer : GLSurfaceView.Renderer {

    // Fullscreen quad vertices: X, Y, U, V
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

    // Text texture ID
    private var textTextureId = 0
    private var textureWidth = 0
    private var textureHeight = 0

    // Current view dimensions (used for bitmap sizing)
    private var viewWidth = 0
    private var viewHeight = 0

    // ----- Vertex Shader -----
    private val vertexShaderCode = """
        #version 320 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    // ----- Fragment Shader: Notebook Paper with Text Overlay -----
    private val fragmentShaderCode = """
        #version 320 es
        precision highp float;

        in vec2 vTexCoord;
        out vec4 outColor;

        uniform vec2 uResolution;
        uniform sampler2D uTextTexture;

        void main() {
            vec2 pixelCoord = vTexCoord * uResolution;

            // ---- Paper Background ----
            vec3 paperColor = vec3(0.98, 0.96, 0.90);

            // ---- Horizontal Lines (College Ruled) ----
            float lineSpacing = 30.0;
            float lineWidth = 1.5;
            float gridY = mod(pixelCoord.y, lineSpacing);
            float distToLine = abs(gridY - lineSpacing / 2.0);
            float lineFactor = 1.0 - smoothstep(0.0, lineWidth, distToLine);
            vec3 lineColor = vec3(0.55, 0.60, 0.75);
            vec3 finalColor = mix(paperColor, lineColor, lineFactor);

            // ---- Left Margin (Red Vertical Line) ----
            float marginRatio = 0.12;
            if (uResolution.x > 600.0) {
                float marginX = marginRatio * uResolution.x;
                float distToMargin = abs(pixelCoord.x - marginX);
                float marginFactor = 1.0 - smoothstep(0.0, 1.5, distToMargin);
                vec3 marginColor = vec3(0.75, 0.20, 0.20);
                finalColor = mix(finalColor, marginColor, marginFactor);
            }

            // ---- Subtle Aging ----
            float ageVignette = 1.0 - length(vTexCoord - 0.5) * 0.3;
            vec3 agedColor = vec3(0.92, 0.88, 0.82);
            finalColor = mix(finalColor, agedColor, (1.0 - ageVignette) * 0.15);

            // ---- Text Overlay ----
            vec4 textColor = texture(uTextTexture, vTexCoord);
            // Blend text over paper using alpha
            finalColor = mix(finalColor, textColor.rgb, textColor.a);

            finalColor = clamp(finalColor, 0.0, 1.0);
            outColor = vec4(finalColor, 1.0);
        }
    """.trimIndent()

    // ----- Lifecycle Methods -----

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Compile shaders
        val vertexShader = loadShader(GLES32.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES32.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES32.glCreateProgram().also {
            GLES32.glAttachShader(it, vertexShader)
            GLES32.glAttachShader(it, fragmentShader)
            GLES32.glLinkProgram(it)
        }

        // Get uniform locations
        resolutionUniform = GLES32.glGetUniformLocation(programId, "uResolution")
        textTextureUniform = GLES32.glGetUniformLocation(programId, "uTextTexture")

        // Setup vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices).position(0) }

        // Generate texture for text overlay
        textTextureId = generateTextTexture()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES32.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height

        GLES32.glUseProgram(programId)
        GLES32.glUniform2f(resolutionUniform, width.toFloat(), height.toFloat())
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)

        GLES32.glUseProgram(programId)

        // Bind text texture
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textTextureId)
        GLES32.glUniform1i(textTextureUniform, 0)

        // Enable vertex attributes
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glEnableVertexAttribArray(1)
        vertexBuffer.position(0)
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 16, vertexBuffer)

        // Draw
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)

        GLES32.glDisableVertexAttribArray(0)
        GLES32.glDisableVertexAttribArray(1)
    }

    // ----- Text Rendering API -----

    /**
     * Update the text overlay with a new TextOverlay.
     * This renders the text to a Bitmap and uploads it as a texture.
     * Call this from the UI thread when text changes.
     */
    fun setTextOverlay(textOverlay: TextOverlay) {
        if (viewWidth == 0 || viewHeight == 0) {
            // View not ready yet; ignore
            return
        }

        val bitmap = renderTextToBitmap(textOverlay, viewWidth, viewHeight)
        uploadTexture(bitmap)
    }

    /**
     * Clear the text overlay (show no text).
     */
    fun clearTextOverlay() {
        // Upload a fully transparent bitmap
        val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        uploadTexture(bitmap)
    }

    // ----- Private Helpers -----

    private fun renderTextToBitmap(textOverlay: TextOverlay, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val paint = android.graphics.Paint().apply {
            color = textOverlay.color
            textSize = textOverlay.textSize
            isAntiAlias = true
            isSubpixelText = true
            textAlign = android.graphics.Paint.Align.LEFT
        }

        // Calculate Y position based on line number
        val lineSpacing = 30f  // must match shader
        val baselineOffset = textOverlay.yOffset
        val y = (textOverlay.lineNumber * lineSpacing) + baselineOffset

        canvas.drawText(textOverlay.text, textOverlay.xOffset, y, paint)
        return bitmap
    }

    private fun uploadTexture(bitmap: Bitmap) {
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textTextureId)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    private fun generateTextTexture(): Int {
        val textures = IntArray(1)
        GLES32.glGenTextures(1, textures, 0)
        val id = textures[0]

        // Initialize with a transparent 1x1 texture
        val tempBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        tempBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
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
}
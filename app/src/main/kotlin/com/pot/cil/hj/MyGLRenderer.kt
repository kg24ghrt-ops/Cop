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
    private var textTextureId = 0
    private var viewWidth = 0
    private var viewHeight = 0

    // Optimized Vertex Shader: calculations moved to vertex stage
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

    // Optimized Fragment Shader: mediump precision, no branches
    private val fragmentShaderCode = """
        #version 320 es
        precision mediump float;

        in vec2 vPixelCoord;
        in vec2 vTexCoord;
        out vec4 outColor;

        uniform sampler2D uTextTexture;
        uniform vec3 uPaperColor;
        uniform vec3 uLineColor;
        uniform vec3 uMarginColor;
        uniform vec3 uAgedColor;
        uniform float uLineSpacing;
        uniform float uLineWidth;
        uniform float uMarginRatio;
        uniform float uVignetteStrength;

        void main() {
            vec3 finalColor = uPaperColor;

            float gridY = mod(vPixelCoord.y, uLineSpacing);
            float distToLine = abs(gridY - uLineSpacing * 0.5);
            float lineFactor = 1.0 - smoothstep(0.0, uLineWidth, distToLine);
            finalColor = mix(finalColor, uLineColor, lineFactor);

            float marginActive = step(600.0, vPixelCoord.x);
            float marginX = uMarginRatio * vPixelCoord.x;
            float distToMargin = abs(vPixelCoord.x - marginX);
            float marginFactor = 1.0 - smoothstep(0.0, 1.5, distToMargin);
            marginFactor *= marginActive;
            finalColor = mix(finalColor, uMarginColor, marginFactor);

            float ageVignette = 1.0 - length(vTexCoord - 0.5) * uVignetteStrength;
            finalColor = mix(finalColor, uAgedColor, (1.0 - ageVignette) * 0.15);

            vec4 textColor = texture(uTextTexture, vTexCoord);
            finalColor = mix(finalColor, textColor.rgb, textColor.a);

            finalColor = clamp(finalColor, 0.0, 1.0);
            outColor = vec4(finalColor, 1.0);
        }
    """.trimIndent()

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        val vertexShader = loadShader(GLES32.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES32.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES32.glCreateProgram().also {
            GLES32.glAttachShader(it, vertexShader)
            GLES32.glAttachShader(it, fragmentShader)
            GLES32.glLinkProgram(it)
        }

        resolutionUniform = GLES32.glGetUniformLocation(programId, "uResolution")
        textTextureUniform = GLES32.glGetUniformLocation(programId, "uTextTexture")

        // Set constant uniforms once
        GLES32.glUseProgram(programId)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(programId, "uPaperColor"), 0.98f, 0.96f, 0.90f)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(programId, "uLineColor"), 0.55f, 0.60f, 0.75f)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(programId, "uMarginColor"), 0.75f, 0.20f, 0.20f)
        GLES32.glUniform3f(GLES32.glGetUniformLocation(programId, "uAgedColor"), 0.92f, 0.88f, 0.82f)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(programId, "uLineSpacing"), 30.0f)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(programId, "uLineWidth"), 1.5f)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(programId, "uMarginRatio"), 0.12f)
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

        GLES32.glUseProgram(programId)
        GLES32.glUniform2f(resolutionUniform, width.toFloat(), height.toFloat())
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

    fun setTextOverlay(textOverlay: TextOverlay) {
        if (viewWidth == 0 || viewHeight == 0) return
        val bitmap = renderTextToBitmap(textOverlay, viewWidth, viewHeight)
        uploadTexture(bitmap)
    }

    fun clearTextOverlay() {
        val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        uploadTexture(bitmap)
    }

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

        val lineSpacing = 30f
        val baselineOffset = textOverlay.yOffset
        val y = (textOverlay.lineNumber * lineSpacing) + baselineOffset
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
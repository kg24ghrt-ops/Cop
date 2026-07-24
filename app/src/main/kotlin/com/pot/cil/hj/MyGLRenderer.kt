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
        // Positions   // Texture Coords
        -1.0f,  1.0f,  0.0f, 0.0f, // Top-left
        -1.0f, -1.0f,  0.0f, 1.0f, // Bottom-left
         1.0f,  1.0f,  1.0f, 0.0f, // Top-right
         1.0f, -1.0f,  1.0f, 1.0f  // Bottom-right
    )
    private lateinit var vertexBuffer: FloatBuffer
    private var programId = 0
    private var textureId = 0

    // Shader source code (GLSL 320 ES)
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

    private val fragmentShaderCode = """
        #version 320 es
        precision highp float;
        in vec2 vTexCoord;
        out vec4 outColor;
        uniform sampler2D uTexture;

        // ==========================================
        // PLACE YOUR "INK ENGINE" SHADER MAGIC HERE
        // ==========================================
        void main() {
            vec4 color = texture(uTexture, vTexCoord);
            // Example: Invert colors for a "negative ink" effect
            // outColor = vec4(1.0 - color.rgb, color.a);
            
            // Default: Pass through
            outColor = color;
        }
    """.trimIndent()

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Set clear color (deep dark gray for contrast)
        GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // 1. Compile Shaders
        val vertexShader = loadShader(GLES32.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES32.GL_FRAGMENT_SHADER, fragmentShaderCode)
        programId = GLES32.glCreateProgram().also {
            GLES32.glAttachShader(it, vertexShader)
            GLES32.glAttachShader(it, fragmentShader)
            GLES32.glLinkProgram(it)
        }

        // 2. Setup Vertex Buffer
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices).position(0) }

        // 3. Generate a dummy texture (replace with your rendered Android text later)
        textureId = generateDummyTexture()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES32.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)

        // Use our shader program
        GLES32.glUseProgram(programId)

        // Bind texture
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureId)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(programId, "uTexture"), 0)

        // Enable vertex attributes
        GLES32.glEnableVertexAttribArray(0)
        GLES32.glEnableVertexAttribArray(1)
        vertexBuffer.position(0)
        GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES32.glVertexAttribPointer(1, 2, GLES32.GL_FLOAT, false, 16, vertexBuffer)

        // Draw fullscreen quad
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)

        // Disable attributes
        GLES32.glDisableVertexAttribArray(0)
        GLES32.glDisableVertexAttribArray(1)
    }

    // ----- Helper: Shader Compiler -----
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

    // ----- Helper: Dummy Texture (Replace with your rendered text Bitmap) -----
    private fun generateDummyTexture(): Int {
        // Create a simple 256x256 gradient texture for testing
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            textSize = 40f
            color = android.graphics.Color.WHITE
        }
        canvas.drawColor(android.graphics.Color.DKGRAY)
        canvas.drawText("Ink Engine", 20f, 120f, paint)
        canvas.drawText("GLES 3.2", 20f, 180f, paint)

        return GLES32.glGenTextures(1, intArrayOf(0), 0).also { id ->
            GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, id)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
            GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }
    }

    // Public function to update texture with a new Bitmap (e.g., from Android Canvas)
    fun updateTexture(newBitmap: Bitmap) {
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, newBitmap, 0)
    }
}
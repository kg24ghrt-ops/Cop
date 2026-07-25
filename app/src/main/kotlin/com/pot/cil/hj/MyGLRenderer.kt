package com.pot.cil.hj

import android.opengl.GLES32
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MyGLRenderer : GLSurfaceView.Renderer {

    // Fullscreen quad vertices: X, Y, U, V
    private val quadVertices = floatArrayOf(
        -1.0f,  1.0f,  0.0f, 0.0f, // top-left
        -1.0f, -1.0f,  0.0f, 1.0f, // bottom-left
         1.0f,  1.0f,  1.0f, 0.0f, // top-right
         1.0f, -1.0f,  1.0f, 1.0f  // bottom-right
    )
    private lateinit var vertexBuffer: FloatBuffer
    private var programId = 0

    // Uniform location for resolution
    private var resolutionUniform = -1

    // ----- Vertex Shader (unchanged) -----
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

    // ----- FRAGMENT SHADER: Procedural Lined Paper -----
    private val fragmentShaderCode = """
        #version 320 es
        precision highp float;

        in vec2 vTexCoord;
        out vec4 outColor;

        uniform vec2 uResolution;   // screen size in pixels

        // Pseudo-random function for grain and blemishes
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
        }

        void main() {
            // Convert to pixel coordinates
            vec2 pixelCoord = vTexCoord * uResolution;

            // ---- 1. Paper background (warm off-white) ----
            vec3 paperColor = vec3(0.98, 0.96, 0.90);

            // ---- 2. Grain (micro‑texture) ----
            float grain = hash(pixelCoord * 0.5) * 0.03;   // 3% noise
            paperColor += grain - 0.015;

            // ---- 3. Blemishes (low‑frequency spots) ----
            float blemish = hash(pixelCoord * 0.02) * 0.02;
            paperColor -= blemish;
            paperColor = clamp(paperColor, 0.0, 1.0);

            // ---- 4. Lined grid ----
            float lineWidth = 2.0;      // pixels
            float spacing   = 40.0;     // pixels between lines

            float gridY = mod(pixelCoord.y, spacing);
            float distToLine = abs(gridY - spacing / 2.0);
            float lineFactor = 1.0 - smoothstep(0.0, lineWidth, distToLine);

            vec3 lineColor = vec3(0.6, 0.6, 0.8); // soft blue‑grey

            // Mix paper and line
            vec3 finalColor = mix(paperColor, lineColor, lineFactor);

            // ---- 5. Subtle vignette (darken edges) ----
            vec2 center = vec2(0.5, 0.5);
            float vignette = 1.0 - length(vTexCoord - center) * 0.6;
            finalColor *= vignette;

            outColor = vec4(finalColor, 1.0);
        }
    """.trimIndent()

    // ----- Lifecycle methods -----

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

        // Get uniform location for resolution
        resolutionUniform = GLES32.glGetUniformLocation(programId, "uResolution")

        // Setup vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices).position(0) }

        // No texture needed anymore – we draw everything procedurally
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES32.glViewport(0, 0, width, height)

        // Pass the screen resolution to the shader
        GLES32.glUseProgram(programId)
        GLES32.glUniform2f(resolutionUniform, width.toFloat(), height.toFloat())
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)

        // Use our shader program
        GLES32.glUseProgram(programId)

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

    // ----- Helper: shader compiler -----
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
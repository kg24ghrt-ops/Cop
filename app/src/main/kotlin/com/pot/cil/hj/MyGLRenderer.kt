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
        -1.0f,  1.0f,  0.0f, 0.0f,
        -1.0f, -1.0f,  0.0f, 1.0f,
         1.0f,  1.0f,  1.0f, 0.0f,
         1.0f, -1.0f,  1.0f, 1.0f
    )
    private lateinit var vertexBuffer: FloatBuffer
    private var programId = 0
    private var resolutionUniform = -1

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

    // ----- FRAGMENT SHADER: Realistic Notebook Paper -----
    private val fragmentShaderCode = """
        #version 320 es
        precision highp float;

        in vec2 vTexCoord;
        out vec4 outColor;

        uniform vec2 uResolution;

        void main() {
            // Convert to pixel coordinates
            vec2 pixelCoord = vTexCoord * uResolution;

            // ---- 1. Paper Background ----
            // Standard off-white/cream paper color
            vec3 paperColor = vec3(0.98, 0.96, 0.90);

            // ---- 2. Horizontal Lines (College Ruled: 7.1mm / 9/32") ----
            // At typical DPI, 7.1mm ≈ 27 pixels. Wide ruled is 8.7mm ≈ 33 pixels.
            // Using 30 pixels as a good default that scales with resolution.
            float lineSpacing = 30.0;      // College Ruled (7.1mm equivalent)
            float lineWidth = 1.5;         // Thin, feint lines

            // Distance to nearest horizontal line
            float gridY = mod(pixelCoord.y, lineSpacing);
            float distToLine = abs(gridY - lineSpacing / 2.0);
            float lineFactor = 1.0 - smoothstep(0.0, lineWidth, distToLine);

            // Feint-ruled: light blue-grey lines (standard for notebook paper)[reference:15]
            vec3 lineColor = vec3(0.55, 0.60, 0.75);
            vec3 finalColor = mix(paperColor, lineColor, lineFactor);

            // ---- 3. Left Margin (Red Vertical Line) ----
            // Standard: 32mm (1 1/4") from left edge[reference:16][reference:17]
            // Only on trim sizes with width > 6"[reference:18][reference:19]
            float marginRatio = 0.12; // ~32mm on Letter/A4 page

            // Only show margin if screen is wide enough (simulates "width > 6"")
            if (uResolution.x > 600.0) {
                float marginX = marginRatio * uResolution.x;
                float distToMargin = abs(pixelCoord.x - marginX);
                float marginFactor = 1.0 - smoothstep(0.0, 1.5, distToMargin);

                // Standard red margin color[reference:20]
                vec3 marginColor = vec3(0.75, 0.20, 0.20);
                finalColor = mix(finalColor, marginColor, marginFactor);
            }

            // ---- 4. Very Subtle Aging (Warm Vignette) ----
            // Slight warming at edges for a natural paper look
            float ageVignette = 1.0 - length(vTexCoord - 0.5) * 0.3;
            vec3 agedColor = vec3(0.92, 0.88, 0.82);
            finalColor = mix(finalColor, agedColor, (1.0 - ageVignette) * 0.15);

            // Clamp to ensure valid color range
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

        // Get uniform location for resolution
        resolutionUniform = GLES32.glGetUniformLocation(programId, "uResolution")

        // Setup vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quadVertices).position(0) }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES32.glViewport(0, 0, width, height)

        // Pass screen resolution to shader
        GLES32.glUseProgram(programId)
        GLES32.glUniform2f(resolutionUniform, width.toFloat(), height.toFloat())
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)

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
}
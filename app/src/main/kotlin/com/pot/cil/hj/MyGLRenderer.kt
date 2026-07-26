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
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.2 Renderer for notebook paper with text overlay.
 * Optimized per Android Developer documentation:
 * - Renders on dedicated thread [8†L18-L19]
 * - Recreates textures when EGL context is lost [9†L16-L25]
 * - Uses RENDERMODE_WHEN_DIRTY for on-demand rendering
 */
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

    // Text texture
    private var textTextureId = 0

    // View dimensions
    @Volatile
    private var viewWidth = 0
    @Volatile
    private var viewHeight = 0

    // Reusable bitmap - protected by synchronized lock
    private var textBitmap: Bitmap? = null
    private val bitmapLock = Any()
    private val isBitmapValid = AtomicBoolean(false)

    // Reusable paint (avoid allocations in render loop)
    private val textPaint = Paint().apply {
        isAntiAlias = true
        isSubpixelText = true
        textAlign = Paint.Align.LEFT
    }

    // Current text overlay state
    @Volatile
    private var pendingTextOverlay: TextOverlay? = null
    private val renderLock = Any()

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

        // Generate texture for text overlay (EGL context created/recreated here) [9†L16-L25]
        textTextureId = generateTextTexture()

        // Re-render any pending text overlay
        pendingTextOverlay?.let { overlay ->
            renderTextToBitmapInternal(overlay)
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES32.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height

        // Recreate bitmap when surface changes size
        synchronized(bitmapLock) {
            textBitmap?.let { 
                if (!it.isRecycled) it.recycle() 
            }
            textBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            isBitmapValid.set(true)
        }

        GLES32.glUseProgram(programId)
        GLES32.glUniform2f(resolutionUniform, width.toFloat(), height.toFloat())

        // Re-render any pending text overlay with new bitmap size
        pendingTextOverlay?.let { overlay ->
            renderTextToBitmapInternal(overlay)
        }
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

    // ----- Public API (Thread-safe) -----

    /**
     * Update the text overlay. Called from UI thread via GLSurfaceView.queueEvent() [9†L13-L15]
     */
    fun setTextOverlay(textOverlay: TextOverlay) {
        if (viewWidth == 0 || viewHeight == 0) {
            // Store for later when surface is ready
            pendingTextOverlay = textOverlay
            return
        }
        pendingTextOverlay = textOverlay
        renderTextToBitmapInternal(textOverlay)
    }

    fun clearTextOverlay() {
        pendingTextOverlay = null
        synchronized(bitmapLock) {
            if (!isBitmapValid.get()) return
            textBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    uploadTexture(bitmap)
                }
            }
        }
    }

    /**
     * Called when activity pauses - EGL context may be lost [2†L9-L10]
     * Mark bitmap as invalid so it will be recreated.
     */
    fun onPause() {
        isBitmapValid.set(false)
    }

    /**
     * Called when activity resumes - EGL context restored [9†L16-L25]
     */
    fun onResume() {
        // Bitmap will be recreated in onSurfaceChanged
        // Pending text will be re-rendered there
    }

    // ----- Private Helpers -----

    /**
     * Render text to bitmap using Android's Canvas (uses Minikin + Skia under the hood).
     * Thread-safe: uses synchronized lock on bitmapLock.
     */
    private fun renderTextToBitmapInternal(textOverlay: TextOverlay) {
        synchronized(bitmapLock) {
            if (!isBitmapValid.get()) {
                // Bitmap not ready, skip
                return
            }

            val bitmap = textBitmap
            if (bitmap == null || bitmap.isRecycled) {
                // Bitmap was recycled, recreate it
                if (viewWidth > 0 && viewHeight > 0) {
                    textBitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
                    isBitmapValid.set(true)
                } else {
                    return
                }
            }

            val currentBitmap = textBitmap ?: return
            if (currentBitmap.isRecycled) {
                isBitmapValid.set(false)
                return
            }

            val canvas = Canvas(currentBitmap)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            textPaint.color = textOverlay.color
            textPaint.textSize = textOverlay.textSize

            val lineSpacing = 30f
            val y = (textOverlay.lineNumber * lineSpacing) + textOverlay.yOffset

            canvas.drawText(textOverlay.text, textOverlay.xOffset, y, textPaint)

            // Upload to GPU - DO NOT recycle bitmap here [3†L10-L11]
            uploadTexture(currentBitmap)
        }
    }

    /**
     * Upload bitmap to GPU texture using GLUtils.texImage2D.
     * IMPORTANT: Does NOT recycle the bitmap - lifecycle managed separately [7†L30-L35]
     */
    private fun uploadTexture(bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            return
        }
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textTextureId)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0)
        // CRITICAL: Do NOT call bitmap.recycle() here!
        // The bitmap is reused for future renders. Recycling would cause
        // "Canvas: trying to use a recycled bitmap" error [0†L10-L12]
    }

    private fun generateTextTexture(): Int {
        val textures = IntArray(1)
        GLES32.glGenTextures(1, textures, 0)
        val id = textures[0]

        // Initialize with transparent 1x1 texture
        val tempBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        tempBitmap.eraseColor(Color.TRANSPARENT)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, id)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, tempBitmap, 0)
        tempBitmap.recycle() // Safe to recycle this one - it's not reused
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
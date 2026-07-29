package com.pot.cil.hj

import android.content.Context
import android.graphics.*
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.min

class MyGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // ---- Paper specifications ----
    private val LINE_SPACING_MM = 7.1f
    private val TOP_MARGIN_MM = 32f
    private val LEFT_MARGIN_MM = 32f
    private val BOTTOM_MARGIN_MM = 12.7f
    private val TOTAL_LINES = 32

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
    var humanizeFactor = 0.6f

    // Transform (same Matrix as before)
    private val contentMatrix = Matrix()
    private val mvpMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    // GL resources
    private var program = 0
    private var uMvpLoc = 0
    private var uTextureLoc = 0
    private var uColorLoc = 0
    private var uAlphaLoc = 0
    private var uResolutionLoc = 0
    private var uVignetteRadiusLoc = 0
    private var uCharSizeLoc = 0

    // VBOs
    private var linesVbo = 0
    private var marginVbo = 0
    private var highlightVbo = 0
    private var vignetteVbo = 0
    private var grainVbo = 0
    private var textVbo = 0
    private var textInstanceVbo = 0

    // Textures
    private var fontAtlasTexture = 0
    private var grainTexture = 0

    // Native renderer handle
    private var nativeHandle = 0L

    // Direct buffer for instance data (x, y, rotation, uvOffset, scale, alpha)
    private lateinit var instanceBuffer: FloatBuffer
    private val maxInstances = 4096

    // Dimensions
    private var viewWidth = 0
    private var viewHeight = 0

    // ---- JNI methods ----
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetDimensions(handle: Long, width: Int, height: Int,
                                             topMargin: Float, spacing: Float,
                                             leftMargin: Float, bottomMargin: Float,
                                             totalLines: Int)
    private external fun nativeSetTextLine(handle: Long, line: Int, text: String)
    private external fun nativeClearText(handle: Long)
    private external fun nativeSetSelectedLine(handle: Long, line: Int)
    private external fun nativeSetHumanize(handle: Long, factor: Float)
    // Returns number of instances written to instanceBuffer (each instance = 6 floats: x, y, rot, uvX, uvY, alpha)
    private external fun nativeGenerateFrame(handle: Long,
                                             contentMatrix: FloatArray,
                                             instanceBuffer: FloatBuffer,
                                             maxInstances: Int): Int

    init {
        System.loadLibrary("native_renderer")
        nativeHandle = nativeCreate()
        nativeSetHumanize(nativeHandle, humanizeFactor)
    }

    // ======================== GL LIFECYCLE ========================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        initShaders()
        initVbos()
        createFontAtlas()
        generateGrainTexture()
        setupTextQuadVbo()
        setupGrainVbo()
        setupVignetteVbo()

        // Allocate instance buffer
        instanceBuffer = ByteBuffer.allocateDirect(maxInstances * 6 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (width == 0 || height == 0) return
        viewWidth = width
        viewHeight = height
        GLES30.glViewport(0, 0, width, height)

        // Recalculate pixel dimensions (same as Canvas version)
        val dpi = context.resources.displayMetrics.densityDpi.toFloat()
        val pxPerMm = dpi / 25.4f
        val calcSpacing = LINE_SPACING_MM * pxPerMm
        val viewBasedSpacing = (height - (TOP_MARGIN_MM + BOTTOM_MARGIN_MM) * pxPerMm) / TOTAL_LINES
        lineSpacingPx = min(calcSpacing, viewBasedSpacing)
        topMarginPx = TOP_MARGIN_MM * pxPerMm
        leftMarginPx = (LEFT_MARGIN_MM * pxPerMm).coerceAtMost(width * 0.3f)
        bottomMarginPx = BOTTOM_MARGIN_MM * pxPerMm
        val availableHeight = height - topMarginPx - bottomMarginPx
        totalLinesValue = (availableHeight / lineSpacingPx).toInt().coerceAtMost(TOTAL_LINES)
        selectedLine = selectedLine.coerceIn(0, totalLinesValue - 1)

        // Pass to native
        nativeSetDimensions(nativeHandle, width, height, topMarginPx, lineSpacingPx,
            leftMarginPx, bottomMarginPx, totalLinesValue)
        nativeSetSelectedLine(nativeHandle, selectedLine)

        // Build projection matrix (ortho)
        Matrix.orthoM(projMatrix, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)

        // Reset transform
        contentMatrix.reset()
        updateMvpMatrix()

        // Rebuild static VBOs (lines, margin, highlight)
        rebuildStaticVbos()
    }

    override fun onDrawFrame(gl: GL10?) {
        // 1. Let native compute per-character transforms
        val instanceCount = nativeGenerateFrame(nativeHandle,
            contentMatrixToArray(), instanceBuffer, maxInstances)
        instanceBuffer.rewind()

        // 2. Clear with paper color
        GLES30.glClearColor(0.980f, 0.961f, 0.902f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

        // ---- Draw paper grain ----
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grainTexture)
        GLES30.glUniform1i(uTextureLoc, 1)
        GLES30.glUniform1f(uAlphaLoc, 0.18f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, grainVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4*4, 0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4*4, 2*4)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisable(GLES30.GL_BLEND)

        // ---- Draw lines (blue) ----
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUniform4f(uColorLoc, 0.549f, 0.600f, 0.749f, 1.0f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, linesVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2*4, 0)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, totalLinesValue * 2)
        GLES30.glDisableVertexAttribArray(0)

        // ---- Draw margin (red) ----
        GLES30.glUniform4f(uColorLoc, 0.749f, 0.200f, 0.200f, 1.0f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, marginVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2*4, 0)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 2)
        GLES30.glDisableVertexAttribArray(0)

        // ---- Draw selected line highlight ----
        if (selectedLine in 0 until totalLinesValue) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUniform4f(uColorLoc, 0.4f, 0.6f, 1.0f, 0.3f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, highlightVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2*4, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // ---- Draw text (instanced quads) ----
        if (instanceCount > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fontAtlasTexture)
            GLES30.glUniform1i(uTextureLoc, 0)
            GLES30.glUniform1f(uAlphaLoc, 1.0f)

            // Char size (height) in pixels
            val charSize = lineSpacingPx * 0.5f
            GLES30.glUniform1f(uCharSizeLoc, charSize)

            // Bind base quad VBO (position + uv)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, textVbo)
            GLES30.glEnableVertexAttribArray(0) // position
            GLES30.glEnableVertexAttribArray(1) // uv
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4*4, 0)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4*4, 2*4)

            // Bind instance data VBO (6 floats per instance)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, textInstanceVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, instanceCount * 6 * 4,
                                instanceBuffer, GLES30.GL_DYNAMIC_DRAW)

            // Attribute layout: x, y, rot, uvX, uvY, alpha
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glEnableVertexAttribArray(3)
            GLES30.glEnableVertexAttribArray(4)
            GLES30.glEnableVertexAttribArray(5)
            GLES30.glEnableVertexAttribArray(6)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, 6*4, 0)
            GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, 6*4, 4)
            GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, 6*4, 8)
            GLES30.glVertexAttribPointer(5, 2, GLES30.GL_FLOAT, false, 6*4, 12)
            GLES30.glVertexAttribPointer(6, 1, GLES30.GL_FLOAT, false, 6*4, 20)
            // Set divisors
            for (i in 2..6) GLES30.glVertexAttribDivisor(i, 1)

            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, instanceCount)

            // Cleanup
            for (i in 2..6) {
                GLES30.glDisableVertexAttribArray(i)
                GLES30.glVertexAttribDivisor(i, 0)
            }
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // ---- Draw vignette (screen space) ----
        // Save current MVP, set ortho for screen coords
        val savedMvp = mvpMatrix.clone()
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.orthoM(mvpMatrix, 0, 0f, viewWidth.toFloat(), viewHeight.toFloat(), 0f, -1f, 1f)
        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)

        GLES30.glUniform4f(uColorLoc, 0f, 0f, 0f, 0.12f)
        GLES30.glUniform2f(uResolutionLoc, viewWidth.toFloat(), viewHeight.toFloat())
        GLES30.glUniform1f(uVignetteRadiusLoc, 0.9f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vignetteVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2*4, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)

        // Restore MVP
        System.arraycopy(savedMvp, 0, mvpMatrix, 0, 16)
        GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0)
    }

    // ======================== HELPER FUNCTIONS ========================

    private fun updateMvpMatrix() {
        val content = FloatArray(16)
        Matrix.setIdentityM(content, 0)
        val values = FloatArray(9)
        contentMatrix.getValues(values)
        // Map android.graphics.Matrix to 4x4 column-major
        content[0] = values[0]
        content[1] = values[3]
        content[4] = values[1]
        content[5] = values[4]
        content[12] = values[2]
        content[13] = values[5]
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, content, 0)
    }

    private fun contentMatrixToArray(): FloatArray {
        val v = FloatArray(9)
        contentMatrix.getValues(v)
        return v
    }

    private fun clampPan() {
        val pts = floatArrayOf(0f, 0f, viewWidth.toFloat(), 0f, 0f, viewHeight.toFloat(), viewWidth.toFloat(), viewHeight.toFloat())
        contentMatrix.mapPoints(pts)
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        for (i in pts.indices step 2) {
            minX = min(minX, pts[i])
            maxX = max(maxX, pts[i])
            minY = min(minY, pts[i + 1])
            maxY = max(maxY, pts[i + 1])
        }
        val dx = if (maxX < viewWidth * 0.2f) viewWidth * 0.2f - maxX
                 else if (minX > viewWidth * 0.8f) viewWidth * 0.8f - minX
                 else 0f
        val dy = if (maxY < viewHeight * 0.2f) viewHeight * 0.2f - maxY
                 else if (minY > viewHeight * 0.8f) viewHeight * 0.8f - minY
                 else 0f
        if (dx != 0f || dy != 0f) {
            contentMatrix.postTranslate(dx, dy)
        }
    }

    // ---- VBO Setup ----
    private fun initVbos() {
        val buffers = IntArray(7)
        GLES30.glGenBuffers(7, buffers, 0)
        linesVbo = buffers[0]
        marginVbo = buffers[1]
        highlightVbo = buffers[2]
        vignetteVbo = buffers[3]
        grainVbo = buffers[4]
        textVbo = buffers[5]
        textInstanceVbo = buffers[6]
    }

    private fun setupTextQuadVbo() {
        // Quad from -0.5 to 0.5, with UV for full character cell in atlas
        val quad = floatArrayOf(
            -0.5f, -0.5f, 0f, 1f,
             0.5f, -0.5f, 1f, 1f,
            -0.5f,  0.5f, 0f, 0f,
             0.5f,  0.5f, 1f, 0f
        )
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quad).rewind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, textVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    private fun setupGrainVbo() {
        // Full screen quad with texture coords for repeat (use large values)
        val vertices = floatArrayOf(
            0f, 0f, 0f, 0f,
            viewWidth.toFloat(), 0f, viewWidth.toFloat() / 256f, 0f,
            0f, viewHeight.toFloat(), 0f, viewHeight.toFloat() / 256f,
            viewWidth.toFloat(), viewHeight.toFloat(), viewWidth.toFloat() / 256f, viewHeight.toFloat() / 256f
        )
        val buf = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(vertices).rewind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, grainVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    private fun setupVignetteVbo() {
        val vertices = floatArrayOf(
            0f, 0f,
            viewWidth.toFloat(), 0f,
            0f, viewHeight.toFloat(),
            viewWidth.toFloat(), viewHeight.toFloat()
        )
        val buf = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(vertices).rewind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vignetteVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    private fun rebuildStaticVbos() {
        // Lines
        val lineVertices = FloatArray(totalLinesValue * 4)
        var idx = 0
        for (i in 0 until totalLinesValue) {
            val y = topMarginPx + i * lineSpacingPx + lineSpacingPx / 2f
            lineVertices[idx++] = leftMarginPx
            lineVertices[idx++] = y
            lineVertices[idx++] = viewWidth.toFloat()
            lineVertices[idx++] = y
        }
        val lineBuf = ByteBuffer.allocateDirect(lineVertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        lineBuf.put(lineVertices).rewind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, linesVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineVertices.size * 4, lineBuf, GLES30.GL_STATIC_DRAW)

        // Margin
        val marginVerts = floatArrayOf(
            leftMarginPx, topMarginPx,
            leftMarginPx, viewHeight - bottomMarginPx
        )
        val marginBuf = ByteBuffer.allocateDirect(marginVerts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        marginBuf.put(marginVerts).rewind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, marginVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, marginVerts.size * 4, marginBuf, GLES30.GL_STATIC_DRAW)

        // Highlight (selected line)
        rebuildHighlightVbo()
    }

    private fun rebuildHighlightVbo() {
        val y = topMarginPx + selectedLine * lineSpacingPx
        val verts = floatArrayOf(
            leftMarginPx, y,
            viewWidth.toFloat(), y,
            leftMarginPx, y + lineSpacingPx,
            viewWidth.toFloat(), y + lineSpacingPx
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts).rewind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, highlightVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
    }

    // ---- Font Atlas ----
    private fun createFontAtlas() {
        // Generate a bitmap with all printable ASCII chars (32-126)
        val charSize = 48 // pixels
        val cols = 16
        val rows = 6 // 95 chars fit in 16*6=96
        val atlasWidth = cols * charSize
        val atlasHeight = rows * charSize
        val bitmap = Bitmap.createBitmap(atlasWidth, atlasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = charSize * 0.8f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        val fm = paint.fontMetrics
        val baseline = charSize / 2f - (fm.ascent + fm.descent) / 2f

        var charIndex = 32
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (charIndex > 126) break
                val x = col * charSize + charSize / 2f
                val y = row * charSize + baseline
                canvas.drawChar(charIndex.toChar(), x, y, paint)
                charIndex++
            }
        }

        // Upload to GL
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        fontAtlasTexture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fontAtlasTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, atlasWidth, atlasHeight, 0,
                            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        bitmap.recycle()
    }

    // ---- Paper Grain ----
    private fun generateGrainTexture() {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val rng = kotlin.random.Random(67890)
        for (i in pixels.indices) {
            val base = 240 + rng.nextInt(16)
            val noise = rng.nextInt(8) - 4
            val gray = (base + noise).coerceIn(0, 255)
            pixels[i] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        val blurred = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val bc = Canvas(blurred)
        val blurPaint = Paint().apply {
            maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)
        }
        bc.drawBitmap(bmp, 0f, 0f, blurPaint)
        bmp.recycle()

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        grainTexture = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grainTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)

        val buffer = ByteBuffer.allocateDirect(blurred.byteCount)
        blurred.copyPixelsToBuffer(buffer)
        buffer.rewind()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, size, size, 0,
                            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        blurred.recycle()
    }

    // ---- Shaders ----
    private fun initShaders() {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES30.glGetProgramInfoLog(program)
            throw RuntimeException("Shader link failed: $info")
        }

        uMvpLoc = GLES30.glGetUniformLocation(program, "uMvp")
        uColorLoc = GLES30.glGetUniformLocation(program, "uColor")
        uTextureLoc = GLES30.glGetUniformLocation(program, "uTexture")
        uAlphaLoc = GLES30.glGetUniformLocation(program, "uAlpha")
        uResolutionLoc = GLES30.glGetUniformLocation(program, "uResolution")
        uVignetteRadiusLoc = GLES30.glGetUniformLocation(program, "uVignetteRadius")
        uCharSizeLoc = GLES30.glGetUniformLocation(program, "uCharSize")
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(shader)
            throw RuntimeException("Shader compile error: $info")
        }
        return shader
    }

    // ---- Shader sources (ES 3.0) ----
    private val VERTEX_SHADER_CODE = """
        #version 300 es
        uniform mat4 uMvp;
        uniform float uCharSize;

        in vec2 aPosition;
        in vec2 aTexCoord;
        in float aInstanceX;
        in float aInstanceY;
        in float aInstanceRot;
        in vec2 aInstanceUvOffset;
        in float aInstanceAlpha;

        out vec2 vTexCoord;
        out float vAlpha;

        void main() {
            float c = cos(aInstanceRot);
            float s = sin(aInstanceRot);
            vec2 pos = aPosition * uCharSize;
            vec2 rotated = vec2(pos.x * c - pos.y * s, pos.x * s + pos.y * c);
            vec2 finalPos = rotated + vec2(aInstanceX, aInstanceY);
            gl_Position = uMvp * vec4(finalPos, 0.0, 1.0);
            // UV: base + offset (character cell within atlas)
            vec2 uv = aTexCoord * (1.0 / 16.0) + aInstanceUvOffset; // 16 columns
            vTexCoord = uv;
            vAlpha = aInstanceAlpha;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER_CODE = """
        #version 300 es
        precision highp float;
        uniform vec4 uColor;
        uniform sampler2D uTexture;
        uniform float uAlpha;
        uniform vec2 uResolution;
        uniform float uVignetteRadius;

        in vec2 vTexCoord;
        in float vAlpha;
        out vec4 fragColor;

        void main() {
            // For text, use texture
            vec4 texColor = texture(uTexture, vTexCoord);
            if (texColor.a < 0.01) {
                // Use color for non-texture draws (lines, etc.)
                fragColor = uColor;
            } else {
                fragColor = vec4(texColor.rgb, texColor.a * uAlpha * vAlpha);
            }
            // Vignette (if uResolution is set)
            if (uResolution.x > 0.0 && uResolution.y > 0.0) {
                vec2 center = uResolution * 0.5;
                float radius = length(uResolution) * 0.5 * uVignetteRadius;
                float dist = distance(gl_FragCoord.xy, center);
                float alpha = smoothstep(radius * 0.7, radius, dist);
                fragColor = vec4(fragColor.rgb, fragColor.a * (1.0 - alpha * 0.12));
            }
        }
    """.trimIndent()

    // ======================== PUBLIC API (Identical to original) ========================

    fun setTextOnLine(lineNumber: Int, text: String) {
        val safeLine = lineNumber.coerceIn(0, totalLinesValue - 1)
        nativeSetTextLine(nativeHandle, safeLine, text)
        selectedLine = safeLine
        rebuildHighlightVbo()
    }

    fun getTextOnLine(lineNumber: Int): String? {
        // We could fetch from native, but for simplicity we maintain a cache in Kotlin.
        // However, we'll keep native as source of truth; we can add a getter if needed.
        // For now, return null if not found.
        return null // override with a map if needed
    }

    fun clearAllText() {
        nativeClearText(nativeHandle)
    }

    fun setSelectedLine(lineNumber: Int) {
        selectedLine = lineNumber.coerceIn(0, totalLinesValue - 1)
        nativeSetSelectedLine(nativeHandle, selectedLine)
        rebuildHighlightVbo()
    }

    fun getTotalLines(): Int = totalLinesValue
    fun getLineHeightPixels(): Float = lineSpacingPx
    fun getTopMarginPixels(): Float = topMarginPx
    fun getLeftMarginPixels(): Float = leftMarginPx
    fun getLineSpacingPixels(): Float = lineSpacingPx

    // ---- Pan/Zoom ----
    fun setPan(dx: Float, dy: Float) {
        contentMatrix.postTranslate(dx, dy)
        clampPan()
        updateMvpMatrix()
    }

    fun setZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        val pts = floatArrayOf(1f, 0f)
        contentMatrix.mapVectors(pts)
        val currentScale = pts[0]
        val newScale = currentScale * scaleFactor
        if (newScale < 0.5f || newScale > 3.0f) return

        val invertedMatrix = Matrix()
        contentMatrix.invert(invertedMatrix)
        val focus = floatArrayOf(focusX, focusY)
        invertedMatrix.mapPoints(focus)
        contentMatrix.postScale(scaleFactor, scaleFactor, focus[0], focus[1])
        clampPan()
        updateMvpMatrix()
    }

    fun resetTransform() {
        contentMatrix.reset()
        clampPan()
        updateMvpMatrix()
    }

    fun cleanup() {
        nativeDestroy(nativeHandle)
        // Delete GL resources
        val buffers = intArrayOf(linesVbo, marginVbo, highlightVbo, vignetteVbo, grainVbo, textVbo, textInstanceVbo)
        GLES30.glDeleteBuffers(buffers.size, buffers, 0)
        val textures = intArrayOf(fontAtlasTexture, grainTexture)
        GLES30.glDeleteTextures(textures.size, textures, 0)
        GLES30.glDeleteProgram(program)
    }
}
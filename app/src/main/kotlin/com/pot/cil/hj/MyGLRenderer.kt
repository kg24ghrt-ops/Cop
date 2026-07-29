package com.pot.cil.hj

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MyGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private var nativeHandle = 0L
    private var cachedTotalLines = 32
    private var cachedLineSpacing = 30f
    private var cachedTopMargin = 40f
    private var cachedLeftMargin = 40f
    private var fontAtlasCreated = false

    // ---- JNI methods ----
    private external fun nativeCreateRenderer(): Long
    private external fun nativeDestroyRenderer(handle: Long)
    private external fun nativeResize(handle: Long, width: Int, height: Int)
    private external fun nativeSetPaperParams(handle: Long, top: Float, spacing: Float,
                                              left: Float, bottom: Float, lines: Int)
    private external fun nativeSetTextLine(handle: Long, line: Int, text: String)
    private external fun nativeClearText(handle: Long)
    private external fun nativeSetSelectedLine(handle: Long, line: Int)
    private external fun nativeSetPan(handle: Long, dx: Float, dy: Float)
    private external fun nativeSetZoom(handle: Long, scale: Float, focusX: Float, focusY: Float)
    private external fun nativeResetTransform(handle: Long)
    private external fun nativeDrawFrame(handle: Long)
    private external fun nativeCreateFontAtlas(handle: Long, width: Int, height: Int, pixels: ByteArray)

    init {
        System.loadLibrary("native_renderer")
        nativeHandle = nativeCreateRenderer()
        // Font atlas is created later in onSurfaceCreated (GL context ready)
    }

    private fun createAndUploadFontAtlas() {
        if (fontAtlasCreated) return
        val charSize = 48
        val cols = 16
        val rows = 6
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
                canvas.drawText(charIndex.toChar().toString(), x, y, paint)
                charIndex++
            }
        }

        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        nativeCreateFontAtlas(nativeHandle, atlasWidth, atlasHeight, buffer.array())
        bitmap.recycle()
        fontAtlasCreated = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // GL context is ready – create the font atlas now
        createAndUploadFontAtlas()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        nativeResize(nativeHandle, width, height)

        val dpi = context.resources.displayMetrics.densityDpi.toFloat()
        val pxPerMm = dpi / 25.4f
        val lineSpacingPx = kotlin.math.min(
            7.1f * pxPerMm,
            (height - (32f + 12.7f) * pxPerMm) / 32f
        )
        val topMarginPx = 32f * pxPerMm
        val leftMarginPx = (32f * pxPerMm).coerceAtMost(width * 0.3f)
        val bottomMarginPx = 12.7f * pxPerMm
        val totalLines = ((height - topMarginPx - bottomMarginPx) / lineSpacingPx)
            .toInt()
            .coerceAtMost(32)

        cachedTotalLines = totalLines
        cachedLineSpacing = lineSpacingPx
        cachedTopMargin = topMarginPx
        cachedLeftMargin = leftMarginPx

        nativeSetPaperParams(nativeHandle, topMarginPx, lineSpacingPx,
            leftMarginPx, bottomMarginPx, totalLines)
    }

    override fun onDrawFrame(gl: GL10?) {
        nativeDrawFrame(nativeHandle)
    }

    // ---- Public API (unchanged) ----
    fun setTextOnLine(lineNumber: Int, text: String) {
        nativeSetTextLine(nativeHandle, lineNumber, text)
    }

    fun getTextOnLine(lineNumber: Int): String? = null

    fun clearAllText() {
        nativeClearText(nativeHandle)
    }

    fun setSelectedLine(lineNumber: Int) {
        nativeSetSelectedLine(nativeHandle, lineNumber)
    }

    fun getTotalLines(): Int = cachedTotalLines
    fun getLineHeightPixels(): Float = cachedLineSpacing
    fun getTopMarginPixels(): Float = cachedTopMargin
    fun getLeftMarginPixels(): Float = cachedLeftMargin
    fun getLineSpacingPixels(): Float = cachedLineSpacing

    fun setPan(dx: Float, dy: Float) {
        nativeSetPan(nativeHandle, dx, dy)
    }

    fun setZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        nativeSetZoom(nativeHandle, scaleFactor, focusX, focusY)
    }

    fun resetTransform() {
        nativeResetTransform(nativeHandle)
    }

    fun cleanup() {
        nativeDestroyRenderer(nativeHandle)
    }
}
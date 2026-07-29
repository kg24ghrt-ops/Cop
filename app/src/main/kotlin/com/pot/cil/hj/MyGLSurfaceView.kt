package com.pot.cil.hj

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.*
import android.view.inputmethod.InputMethodManager

class MyGLSurfaceView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    private val renderer = MyGLRenderer(context)
    private var fakeEditText: FakeEditText? = null
    private var currentLine = 3

    // Gesture state
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false

    init {
        isFocusableInTouchMode = true
        isFocusable = true

        // Request OpenGL ES 3.0
        setEGLContextClientVersion(3)
        // Choose a config that works on all devices
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    // ---- Public API (Identical to original) ----

    fun setFakeEditText(editText: FakeEditText) {
        fakeEditText = editText
        editText.setOnTextChangeListener { newText ->
            updateRenderedText(newText)
        }
    }

    fun updateRenderedText(text: String) {
        renderer.setTextOnLine(currentLine, text)
        requestRender()
    }

    fun clearRenderedText() {
        renderer.clearAllText()
        fakeEditText?.clearText()
        requestRender()
    }

    fun moveToLine(lineNumber: Int) {
        val target = lineNumber.coerceIn(0, renderer.getTotalLines() - 1)
        if (target == currentLine) return
        currentLine = target
        renderer.setSelectedLine(currentLine)
        fakeEditText?.let {
            val lineText = renderer.getTextOnLine(currentLine) ?: ""
            if (it.text.toString() != lineText) {
                it.setText(lineText)
                it.setSelection(lineText.length)
            }
        }
        requestRender()
    }

    fun moveLineUp() = moveToLine(currentLine - 1)
    fun moveLineDown() = moveToLine(currentLine + 1)
    fun getCurrentLine(): Int = currentLine
    fun getTotalLines(): Int = renderer.getTotalLines()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPanX = event.x
                lastPanY = event.y
                isPanning = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning && event.pointerCount == 1) {
                    val dx = event.x - lastPanX
                    val dy = event.y - lastPanY
                    renderer.setPan(dx, dy)
                    lastPanX = event.x
                    lastPanY = event.y
                    requestRender()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                if (Math.abs(event.x - lastPanX) < 20 && Math.abs(event.y - lastPanY) < 20) {
                    val y = event.y - renderer.getTopMarginPixels()
                    val line = (y / renderer.getLineHeightPixels()).toInt()
                    if (line in 0 until renderer.getTotalLines()) {
                        moveToLine(line)
                    }
                    showKeyboard()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { moveLineUp(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveLineDown(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    fun showKeyboard() {
        fakeEditText?.let {
            it.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        fakeEditText?.let {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer.cleanup()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.setZoom(detector.scaleFactor, detector.focusX, detector.focusY)
            requestRender()
            return true
        }
    }
}
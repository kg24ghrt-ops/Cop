package com.pot.cil.hj

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager

class MyGLSurfaceView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    private val renderer = MyGLRenderer(context)
    private var fakeEditText: FakeEditText? = null
    private var currentLine = 3

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusableInTouchMode = true
        isFocusable = true
    }

    fun setFakeEditText(editText: FakeEditText) {
        this.fakeEditText = editText
        editText.setOnTextChangeListener { newText ->
            updateRenderedText(newText)
        }
    }

    // ---- UPDATED METHOD (dynamic alignment) ----
    fun updateRenderedText(text: String) {
        val leftMargin = renderer.getLeftMarginPixels()
        val lineSpacing = renderer.getLineSpacingPixels()

        val xOffset = leftMargin + 10f
        val yOffset = lineSpacing * 0.6f

        val textOverlay = TextOverlay(
            text = text,
            lineNumber = currentLine,
            textSize = 40f,
            color = android.graphics.Color.BLACK,
            xOffset = xOffset,
            yOffset = yOffset
        )
        queueEvent {
            renderer.setTextOverlay(textOverlay, currentLine)
        }
        requestRender()
    }

    fun clearRenderedText() {
        queueEvent {
            renderer.clearTextOverlay()
        }
        fakeEditText?.clearText()
        requestRender()
    }

    fun moveToLine(lineNumber: Int) {
        val totalLines = renderer.getTotalLines()
        currentLine = lineNumber.coerceIn(0, totalLines - 1)
        queueEvent {
            renderer.setSelectedLine(currentLine)
        }
        fakeEditText?.let {
            updateRenderedText(it.text.toString())
        }
        requestRender()
    }

    fun moveLineUp() {
        moveToLine(currentLine - 1)
    }

    fun moveLineDown() {
        moveToLine(currentLine + 1)
    }

    fun getCurrentLine(): Int = currentLine
    fun getTotalLines(): Int = renderer.getTotalLines()

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            val lineHeight = renderer.getLineHeightPixels()
            val topMargin = renderer.getTopMarginPixels()
            val y = event.y

            val relativeY = y - topMargin
            val line = (relativeY / lineHeight).toInt()

            if (line >= 0 && line < renderer.getTotalLines()) {
                moveToLine(line)
            }

            showKeyboard()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveLineUp()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveLineDown()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    fun showKeyboard() {
        fakeEditText?.let { editText ->
            editText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        fakeEditText?.let { editText ->
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
            editText.clearFocus()
        }
    }
}
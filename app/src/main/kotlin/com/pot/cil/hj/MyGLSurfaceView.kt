package com.pot.cil.hj

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager

class MyGLSurfaceView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    private val renderer = MyGLRenderer()
    private var fakeEditText: FakeEditText? = null
    private var currentLine = 3  // Default starting line (0-indexed)

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

    fun updateRenderedText(text: String) {
        val textOverlay = TextOverlay(
            text = text,
            lineNumber = currentLine,
            textSize = 40f,
            color = android.graphics.Color.BLACK,
            xOffset = 40f,
            yOffset = 20f
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
        requestRender()
    }

    fun moveToLine(lineNumber: Int) {
        val totalLines = renderer.getTotalLines()
        currentLine = lineNumber.coerceIn(0, totalLines - 1)
        // Update the highlight
        queueEvent {
            renderer.setSelectedLine(currentLine)
        }
        // Refresh the text on the new line
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

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            // Calculate which line was tapped
            val lineHeight = renderer.getLineHeightPixels()
            val topMargin = renderer.getTopMarginPixels()
            val y = event.y

            // Convert touch Y to line number
            val relativeY = y - topMargin
            val line = (relativeY / lineHeight).toInt()

            if (line >= 0) {
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
        }
    }
}
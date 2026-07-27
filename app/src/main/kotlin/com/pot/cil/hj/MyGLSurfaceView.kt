package com.pot.cil.hj

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager

class MyGLSurfaceView(context: Context, attrs: AttributeSet? = null) :
    GLSurfaceView(context, attrs) {

    private val renderer = MyGLRenderer(context)
    private var fakeEditText: FakeEditText? = null
    private var currentLine = 3

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        // Continuous rendering – no need for manual requestRender() calls
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusableInTouchMode = true
        isFocusable = true
    }

    fun setFakeEditText(editText: FakeEditText) {
        fakeEditText = editText
        editText.setOnTextChangeListener { newText ->
            renderer.setTextOnLine(currentLine, newText)
        }
    }

    fun updateRenderedText(text: String) {
        renderer.setTextOnLine(currentLine, text)
    }

    fun clearRenderedText() {
        renderer.clearAllText()
        fakeEditText?.clearText()
    }

    /**
     * Move selection to a specific line (0‑based).  If the line didn’t change,
     * the method returns immediately to avoid unnecessary work.
     */
    fun moveToLine(lineNumber: Int) {
        val totalLines = renderer.getTotalLines()
        val targetLine = lineNumber.coerceIn(0, totalLines - 1)

        // No change → skip all updates
        if (targetLine == currentLine) return

        currentLine = targetLine
        renderer.setSelectedLine(currentLine)

        // Update the fake EditText to show any existing text on the new line
        fakeEditText?.let {
            val lineText = renderer.getTextOnLine(currentLine) ?: ""
            if (it.text.toString() != lineText) {
                it.setText(lineText)
                it.setSelection(lineText.length)
            }
        }
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

            if (line in 0 until renderer.getTotalLines()) {
                moveToLine(line)
            }

            // Always offer the keyboard; if already on the same line it just ensures focus
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
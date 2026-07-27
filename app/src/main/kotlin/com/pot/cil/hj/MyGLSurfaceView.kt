package com.pot.cil.hj

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager

class MyGLSurfaceView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val renderer = MyGLRenderer(context)
    private var fakeEditText: FakeEditText? = null
    private var currentLine = 3

    init {
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
        renderer.setTextOnLine(currentLine, text)
        invalidate()
    }

    fun clearRenderedText() {
        renderer.clearAllText()
        fakeEditText?.clearText()
        invalidate()
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
        invalidate()
    }

    fun moveLineUp() = moveToLine(currentLine - 1)
    fun moveLineDown() = moveToLine(currentLine + 1)
    fun getCurrentLine(): Int = currentLine
    fun getTotalLines(): Int = renderer.getTotalLines()

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            val y = event.y - renderer.getTopMarginPixels()
            val line = (y / renderer.getLineHeightPixels()).toInt()
            if (line in 0 until renderer.getTotalLines()) {
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderer.onSurfaceChanged(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.onDrawFrame(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer.cleanup()
    }
}
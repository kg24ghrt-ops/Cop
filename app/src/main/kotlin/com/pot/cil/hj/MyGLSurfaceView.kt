package com.pot.cil.hj

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager

class MyGLSurfaceView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    private val renderer = MyGLRenderer()
    private var fakeEditText: FakeEditText? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusableInTouchMode = true
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
            lineNumber = 3,
            textSize = 40f,
            color = android.graphics.Color.BLACK,
            xOffset = 20f,
            yOffset = 20f
        )
        queueEvent {
            renderer.setTextOverlay(textOverlay)
        }
        requestRender()
    }

    fun clearRenderedText() {
        queueEvent {
            renderer.clearTextOverlay()
        }
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            showKeyboard()
            return true
        }
        return super.onTouchEvent(event)
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
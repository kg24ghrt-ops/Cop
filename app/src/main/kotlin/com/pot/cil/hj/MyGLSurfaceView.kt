package com.pot.cil.hj

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager

/**
 * GLSurfaceView that hosts the notebook paper renderer.
 * Optimized per Android Developer documentation:
 * - Uses RENDERMODE_WHEN_DIRTY for on-demand rendering [0†L7-L8]
 * - Manages EGL context and rendering thread [7†L15-L20]
 * - Handles touch events to show keyboard
 */
class MyGLSurfaceView(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    private val renderer = MyGLRenderer()
    private var fakeEditText: FakeEditText? = null
    private var currentText = ""

    companion object {
        private const val TAG = "MyGLSurfaceView"
    }

    init {
        setEGLContextClientVersion(3)

        // Configure EGL for optimal performance [10†L18-L22]
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)

        setRenderer(renderer)

        // CRITICAL PERFORMANCE: Render only when needed [0†L7-L8][7†L19-L20]
        renderMode = RENDERMODE_WHEN_DIRTY

        isFocusableInTouchMode = true
    }

    fun setFakeEditText(editText: FakeEditText) {
        this.fakeEditText = editText
        editText.setOnTextChangeListener { newText ->
            Log.d(TAG, "Text changed: '$newText'")
            currentText = newText
            updateRenderedText(newText)
        }
    }

    fun updateRenderedText(text: String) {
        val textOverlay = TextOverlay(
            text = text,
            lineNumber = 3,
            textSize = 60f,
            color = android.graphics.Color.BLACK,
            xOffset = 40f,
            yOffset = 30f
        )

        // Use queueEvent for thread-safe communication with renderer [8†L18-L20]
        queueEvent {
            renderer.setTextOverlay(textOverlay)
        }
        requestRender() // Trigger redraw since we're in WHEN_DIRTY mode
    }

    fun clearRenderedText() {
        queueEvent {
            renderer.clearTextOverlay()
        }
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            Log.d(TAG, "Screen tapped, showing keyboard")
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

    /**
     * Called when activity resumes - EGL context may have been lost. [8†L21-L26]
     * The renderer handles recreation in onSurfaceCreated.
     */
    override fun onResume() {
        super.onResume()
        // Restore text if needed
        if (currentText.isNotEmpty()) {
            updateRenderedText(currentText)
        }
    }
}
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

        // Make this view focusable in touch mode so we can show the keyboard
        isFocusableInTouchMode = true
    }

    /**
     * Set the FakeEditText instance that will serve as the keyboard bridge.
     * Must be called before any typing interaction.
     */
    fun setFakeEditText(editText: FakeEditText) {
        this.fakeEditText = editText
        // When the edit text changes, update our rendered text
        editText.setOnTextChangeListener { newText ->
            updateRenderedText(newText)
        }
    }

    /**
     * Update the rendered text overlay with the given string.
     * Called both from the EditText callback and externally.
     */
    fun updateRenderedText(text: String) {
        // Use a default line number (e.g., line 3) – you can change this as needed.
        // For dynamic line selection, you can store a variable.
        val lineNumber = 3
        val textOverlay = TextOverlay(
            text = text,
            lineNumber = lineNumber,
            textSize = 40f,
            color = android.graphics.Color.BLACK,
            xOffset = 20f,
            yOffset = 20f
        )
        // Post to UI thread to avoid threading issues
        post {
            renderer.setTextOverlay(textOverlay)
            requestRender()
        }
    }

    /** Clear the rendered text. */
    fun clearRenderedText() {
        post {
            renderer.clearTextOverlay()
            requestRender()
        }
    }

    // ---- Touch handling ----
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            // When the user taps the paper, show the keyboard
            showKeyboard()
            return true
        }
        return super.onTouchEvent(event)
    }

    /** Request focus for the fake EditText and show the soft keyboard. */
    fun showKeyboard() {
        fakeEditText?.let { editText ->
            editText.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Hide the soft keyboard. */
    fun hideKeyboard() {
        fakeEditText?.let { editText ->
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editText.windowToken, 0)
        }
    }
}
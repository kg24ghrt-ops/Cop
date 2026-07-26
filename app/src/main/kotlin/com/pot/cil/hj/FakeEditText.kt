package com.pot.cil.hj

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/**
 * Invisible EditText that acts as a bridge between the Android keyboard
 * and the OpenGL renderer. It captures text input and forwards it via a callback.
 */
class FakeEditText(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {

    private var textChangeListener: ((String) -> Unit)? = null

    init {
        // Make it invisible and non-interfering
        isCursorVisible = false
        setBackgroundResource(android.R.color.transparent)
        setPadding(0, 0, 0, 0)
        layoutParams = LayoutParams(1, 1) // tiny size

        // Configure for text input, disable full-screen mode
        inputType = EditorInfo.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI

        // Listen to text changes
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                textChangeListener?.invoke(s?.toString() ?: "")
            }
        })
    }

    /** Set a callback that receives the current text whenever it changes. */
    fun setOnTextChangeListener(listener: (String) -> Unit) {
        this.textChangeListener = listener
    }

    /** Clear the text programmatically. */
    fun clearText() {
        setText("")
    }
}
package com.pot.cil.hj

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * An invisible EditText that acts as a keyboard input bridge for the notebook view.
 *
 * This class:
 * - Receives keyboard input and forwards text changes to a listener
 * - Stays invisible (INVISIBLE) so it doesn't overlay the notebook
 * - Is focusable so the keyboard can be shown
 *
 * @param context The context used to create the view
 */
class FakeEditText(context: Context) : EditText(context) {

    /**
     * Callback invoked when the text changes.
     * The new text is passed as a String (empty string if cleared).
     */
    private var textChangeListener: ((String) -> Unit)? = null

    init {
        // Ensure this view can receive focus and show the keyboard
        isFocusable = true
        isFocusableInTouchMode = true

        // Listen for text changes and forward them to the listener
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // Not needed for this use case
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Not needed for this use case
            }

            override fun afterTextChanged(s: Editable?) {
                // Called after text is modified – forward the new text to the listener
                textChangeListener?.invoke(s?.toString() ?: "")
            }
        })
    }

    /**
     * Sets a listener that will be called whenever the text changes.
     *
     * @param listener Callback that receives the current text as a String
     */
    fun setOnTextChangeListener(listener: (String) -> Unit) {
        textChangeListener = listener
    }

    /**
     * Clears the text content.
     * This is a safe helper that does not trigger infinite recursion.
     */
    fun clearText() {
        setText("")
    }
}
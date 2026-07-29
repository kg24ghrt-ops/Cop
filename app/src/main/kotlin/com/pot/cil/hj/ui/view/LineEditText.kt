package com.pot.cil.hj.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.AppCompatEditText
import com.pot.cil.hj.ui.theme.NotebookColors

/**
 * Custom EditText that sits on a single ruled line.
 * Handles line navigation, selection, and text input.
 */
class LineEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var lineIndex: Int = 0
    var onLineActionListener: OnLineActionListener? = null

    interface OnLineActionListener {
        fun onNextLine(currentLine: Int)
        fun onPreviousLine(currentLine: Int)
        fun onLineTextChanged(lineIndex: Int, text: String)
        fun onLineSelected(lineIndex: Int)
        fun onLineLongPressed(lineIndex: Int)
    }

    init {
        setupAppearance()
        setupListeners()
    }

    private fun setupAppearance() {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextColor(NotebookColors.InkBlack)
        textSize = 22f
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        imeOptions = EditorInfo.IME_ACTION_NEXT
        isSingleLine = true
    }

    private fun setupListeners() {
        // Text change
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onLineActionListener?.onLineTextChanged(lineIndex, s?.toString() ?: "")
            }
        })

        // Focus and selection
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                onLineActionListener?.onLineSelected(lineIndex)
            }
        }

        // Click to select
        setOnClickListener {
            onLineActionListener?.onLineSelected(lineIndex)
        }

        // Long press for multi-select
        setOnLongClickListener {
            onLineActionListener?.onLineLongPressed(lineIndex)
            true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                onLineActionListener?.onNextLine(lineIndex)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                onLineActionListener?.onPreviousLine(lineIndex)
                true
            }
            KeyEvent.KEYCODE_ENTER -> {
                onLineActionListener?.onNextLine(lineIndex)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onEditorAction(actionCode: Int) {
        if (actionCode == EditorInfo.IME_ACTION_NEXT) {
            onLineActionListener?.onNextLine(lineIndex)
        } else {
            super.onEditorAction(actionCode)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Draw cursor as a hand-drawn style line
        super.onDraw(canvas)
        
        if (isFocused && text?.isEmpty() == true) {
            // Draw a subtle placeholder cursor
            val paint = Paint().apply {
                color = NotebookColors.LineBlue
                strokeWidth = 2f
                alpha = 100
            }
            val x = paddingLeft.toFloat()
            val baseline = baseline.toFloat()
            canvas.drawLine(x, baseline - 20, x, baseline + 10, paint)
        }
    }
}

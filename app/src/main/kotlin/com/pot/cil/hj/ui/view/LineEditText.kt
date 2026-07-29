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
 * Handles line navigation, selection, text input, and auto-wrap overflow.
 */
class LineEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var lineIndex: Int = 0
    var onLineActionListener: OnLineActionListener? = null

    /** Maximum characters before auto-wrapping to next line (approximate) */
    private var maxCharsPerLine: Int = 45

    interface OnLineActionListener {
        fun onNextLine(currentLine: Int)
        fun onPreviousLine(currentLine: Int)
        fun onLineTextChanged(lineIndex: Int, text: String)
        fun onLineSelected(lineIndex: Int)
        fun onLineLongPressed(lineIndex: Int)
        /** Called when text overflows this line - returns overflow text to put on next line */
        fun onLineOverflow(currentLine: Int, overflowText: String)
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
        // Text change with overflow detection
        addTextChangedListener(object : TextWatcher {
            private var beforeText: String = ""
            private var isProcessingOverflow = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isProcessingOverflow) {
                    beforeText = s?.toString() ?: ""
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isProcessingOverflow) return

                val text = s?.toString() ?: ""
                onLineActionListener?.onLineTextChanged(lineIndex, text)

                // Check for overflow - if text is too long, split it
                val overflow = checkOverflow(text)
                if (overflow != null && overflow.isNotEmpty()) {
                    isProcessingOverflow = true
                    // Keep only the part that fits on this line
                    val keepText = text.substring(0, text.length - overflow.length)
                    setText(keepText)
                    setSelection(keepText.length)
                    // Send overflow to next line
                    onLineActionListener?.onLineOverflow(lineIndex, overflow)
                    isProcessingOverflow = false
                }
            }
        })

        // Focus change - ONLY notify, never trigger focus changes from here
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

    /**
     * Check if text exceeds line capacity. Returns the overflow portion
     * that should move to the next line, or null if no overflow.
     */
    private fun checkOverflow(text: String): String? {
        // Measure actual text width vs available width
        val paint = paint
        val availableWidth = width.toFloat() - compoundPaddingLeft - compoundPaddingRight
        if (availableWidth <= 0) {
            // Fallback to character count if width not measured yet
            return if (text.length > maxCharsPerLine) {
                text.substring(maxCharsPerLine)
            } else null
        }

        val textWidth = paint.measureText(text)
        return if (textWidth > availableWidth) {
            // Find how many characters fit
            var fitCount = text.length
            while (fitCount > 0 && paint.measureText(text, 0, fitCount) > availableWidth) {
                fitCount--
            }
            // Try to break at word boundary
            var breakPoint = fitCount
            if (breakPoint < text.length) {
                // Look for last space before break point
                val lastSpace = text.lastIndexOf(' ', breakPoint)
                if (lastSpace > 0 && lastSpace > breakPoint - 15) {
                    breakPoint = lastSpace + 1
                }
            }
            text.substring(breakPoint)
        } else null
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
        super.onDraw(canvas)
        if (isFocused && text?.isEmpty() == true) {
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

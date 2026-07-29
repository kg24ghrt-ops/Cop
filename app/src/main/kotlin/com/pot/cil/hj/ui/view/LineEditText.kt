package com.pot.cil.hj.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.text.TextPaint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.AppCompatEditText
import com.pot.cil.hj.ui.theme.NotebookColors

/**
 * Custom EditText that sits on a single ruled line with handwriting simulation.
 * Renders text with baseline jitter, random spacing, and ink variation.
 */
class LineEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var lineIndex: Int = 0
    var onLineActionListener: OnLineActionListener? = null

    private var maxCharsPerLine: Int = 45
    private val handwritingPaint = HandwritingPaint()

    interface OnLineActionListener {
        fun onNextLine(currentLine: Int)
        fun onPreviousLine(currentLine: Int)
        fun onLineTextChanged(lineIndex: Int, text: String)
        fun onLineSelected(lineIndex: Int)
        fun onLineLongPressed(lineIndex: Int)
        fun onLineOverflow(currentLine: Int, overflowText: String)
    }

    init {
        setupAppearance()
        setupListeners()
    }

    private fun setupAppearance() {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // Don't set text color here - we draw it ourselves
        textSize = 22f
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        imeOptions = EditorInfo.IME_ACTION_NEXT
        isSingleLine = true
        // Use transparent color for default drawing so we can draw ourselves
        setTextColor(android.graphics.Color.TRANSPARENT)
        // But show cursor
        setCursorVisible(true)
    }

    private fun setupListeners() {
        addTextChangedListener(object : TextWatcher {
            private var isProcessingOverflow = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isProcessingOverflow) return

                val text = s?.toString() ?: ""
                onLineActionListener?.onLineTextChanged(lineIndex, text)

                val overflow = checkOverflow(text)
                if (overflow != null && overflow.isNotEmpty()) {
                    isProcessingOverflow = true
                    val keepText = text.substring(0, text.length - overflow.length)
                    setText(keepText)
                    setSelection(keepText.length)
                    onLineActionListener?.onLineOverflow(lineIndex, overflow)
                    isProcessingOverflow = false
                }
            }
        })

        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                onLineActionListener?.onLineSelected(lineIndex)
            }
            invalidate() // Redraw with/without focus styling
        }

        setOnClickListener {
            onLineActionListener?.onLineSelected(lineIndex)
        }

        setOnLongClickListener {
            onLineActionListener?.onLineLongPressed(lineIndex)
            true
        }
    }

    private fun checkOverflow(text: String): String? {
        val paint = paint
        val availableWidth = width.toFloat() - compoundPaddingLeft - compoundPaddingRight
        if (availableWidth <= 0) {
            return if (text.length > maxCharsPerLine) text.substring(maxCharsPerLine) else null
        }

        val textWidth = paint.measureText(text)
        return if (textWidth > availableWidth) {
            var fitCount = text.length
            while (fitCount > 0 && paint.measureText(text, 0, fitCount) > availableWidth) {
                fitCount--
            }
            var breakPoint = fitCount
            if (breakPoint < text.length) {
                val lastSpace = text.lastIndexOf(' ', breakPoint)
                if (lastSpace > 0 && lastSpace > breakPoint - 15) {
                    breakPoint = lastSpace + 1
                }
            }
            text.substring(breakPoint)
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        // Don't call super.onDraw() - we render text ourselves with handwriting effect
        
        val text = text?.toString() ?: ""
        if (text.isEmpty()) {
            // Draw cursor placeholder when empty
            if (isFocused) {
                val paint = Paint().apply {
                    color = NotebookColors.LineBlue
                    strokeWidth = 2f
                    alpha = 100
                }
                canvas.drawLine(
                    paddingLeft.toFloat(),
                    baseline.toFloat() - 20,
                    paddingLeft.toFloat(),
                    baseline.toFloat() + 10,
                    paint
                )
            }
            return
        }

        // Create text paint with current styling
        val textPaint = TextPaint(paint).apply {
            color = NotebookColors.InkBlack
            this.textSize = this@LineEditText.textSize
            isAntiAlias = true
        }

        // Apply handwriting effects
        val seed = lineIndex * 10000 + text.hashCode()
        handwritingPaint.drawHandwrittenText(
            canvas = canvas,
            text = text,
            x = paddingLeft.toFloat(),
            y = baseline.toFloat(),
            paint = textPaint,
            seed = seed
        )

        // Draw cursor if focused
        if (isFocused && selectionStart >= 0) {
            drawCursor(canvas, text, selectionStart)
        }
    }

    private fun drawCursor(canvas: Canvas, text: String, cursorPos: Int) {
        val cursorX = if (cursorPos <= text.length) {
            paint.measureText(text, 0, cursorPos) + paddingLeft
        } else {
            paint.measureText(text) + paddingLeft
        }

        val cursorPaint = Paint().apply {
            color = NotebookColors.InkBlue
            strokeWidth = 2.5f
            alpha = 200
        }

        canvas.drawLine(
            cursorX,
            baseline - textSize * 0.8f,
            cursorX,
            baseline + textSize * 0.2f,
            cursorPaint
        )
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
}

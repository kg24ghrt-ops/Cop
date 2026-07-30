package com.pot.cil.hj.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextPaint
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.widget.AppCompatEditText
import com.pot.cil.hj.ui.theme.NotebookColors

/**
 * Single-line editor with real-scale handwriting rendering.
 * 
 * Real human handwriting:
 * - Text size: ~3.5mm x-height ≈ 22px at 160dpi
 * - This equals roughly 14-15pt font
 * - Line spacing 7.1mm gives comfortable 2mm gap below text
 */
class LineEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var lineIndex: Int = 0
    var onLineActionListener: OnLineActionListener? = null

    private val handwritingPaint = HandwritingPaint()
    
    // Real scale: 3.5mm text = ~22px at baseline
    companion object {
        const val REAL_TEXT_SIZE_PX = 26f       // ~4.1mm, comfortable for handwriting
        const val REAL_TEXT_SIZE_SP = 16f       // For accessibility scaling
    }

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
        textSize = REAL_TEXT_SIZE_SP
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        imeOptions = EditorInfo.IME_ACTION_NEXT
        isSingleLine = true
        
        // Hide default text rendering — we draw ourselves
        setTextColor(android.graphics.Color.TRANSPARENT)
        setCursorVisible(true)
        
        // Use a handwriting-friendly font if available
        typeface = android.graphics.Typeface.DEFAULT
    }

    private fun setupListeners() {
        addTextChangedListener(object : TextWatcher {
            private var isProcessing = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isProcessing) return
                val text = s?.toString() ?: ""
                onLineActionListener?.onLineTextChanged(lineIndex, text)

                val overflow = checkOverflow(text)
                if (overflow != null && overflow.isNotEmpty()) {
                    isProcessing = true
                    val keep = text.substring(0, text.length - overflow.length)
                    setText(keep)
                    setSelection(keep.length)
                    onLineActionListener?.onLineOverflow(lineIndex, overflow)
                    isProcessing = false
                }
            }
        })

        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onLineActionListener?.onLineSelected(lineIndex)
            invalidate()
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
        val available = width.toFloat() - compoundPaddingLeft - compoundPaddingRight
        if (available <= 0) return null
        
        val measured = paint.measureText(text)
        if (measured <= available) return null
        
        var fit = text.length
        while (fit > 0 && paint.measureText(text, 0, fit) > available) fit--
        
        var breakAt = fit
        if (breakAt < text.length) {
            val lastSpace = text.lastIndexOf(' ', breakAt)
            if (lastSpace > 0 && lastSpace > breakAt - 12) breakAt = lastSpace + 1
        }
        return text.substring(breakAt)
    }

    override fun onDraw(canvas: Canvas) {
        val text = text?.toString() ?: ""
        
        if (text.isEmpty() && isFocused) {
            drawEmptyCursor(canvas)
            return
        }

        val textPaint = TextPaint(paint).apply {
            color = NotebookColors.InkBlack
            this.textSize = this@LineEditText.textSize
            isAntiAlias = true
            // Slightly heavier for ballpoint pen feel
            strokeWidth = 1.2f
        }

        // Seed from line + content for consistent but unique rendering
        val seed = lineIndex * 7919 + text.hashCode()
        handwritingPaint.drawHandwrittenText(
            canvas, text,
            paddingLeft.toFloat(),
            baseline.toFloat(),
            textPaint,
            seed
        )

        if (isFocused && selectionStart >= 0) {
            drawCursor(canvas, text, selectionStart)
        }
    }

    private fun drawEmptyCursor(canvas: Canvas) {
        val paint = Paint().apply {
            color = NotebookColors.LineBlue
            strokeWidth = 2f
            alpha = 120
        }
        canvas.drawLine(
            paddingLeft.toFloat(),
            baseline - textSize * 0.7f,
            paddingLeft.toFloat(),
            baseline + textSize * 0.15f,
            paint
        )
    }

    private fun drawCursor(canvas: Canvas, text: String, pos: Int) {
        val x = if (pos <= text.length) {
            paint.measureText(text, 0, pos) + paddingLeft
        } else {
            paint.measureText(text) + paddingLeft
        }

        val paint = Paint().apply {
            color = NotebookColors.InkBlue
            strokeWidth = 2f
            alpha = 180
        }
        canvas.drawLine(
            x, baseline - textSize * 0.75f,
            x, baseline + textSize * 0.2f,
            paint
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { onLineActionListener?.onNextLine(lineIndex); true }
            KeyEvent.KEYCODE_DPAD_UP -> { onLineActionListener?.onPreviousLine(lineIndex); true }
            KeyEvent.KEYCODE_ENTER -> { onLineActionListener?.onNextLine(lineIndex); true }
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

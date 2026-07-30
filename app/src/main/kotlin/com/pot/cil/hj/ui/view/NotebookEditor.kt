package com.pot.cil.hj.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.pot.cil.hj.data.NotebookPage

class NotebookEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    lateinit var paperView: NotebookPaperView
        private set

    private val textContainer: FrameLayout
    private val editTexts = mutableMapOf<Int, LineEditText>()

    private var currentPage = NotebookPage()
    private var activeLineIndex: Int = -1
    private var selectedLines = mutableSetOf<Int>()
    private var isMultiSelectMode = false
    private var isSettingActiveLine = false

    var onPageChanged: ((NotebookPage) -> Unit)? = null

    init {
        paperView = NotebookPaperView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        addView(paperView)

        textContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        addView(textContainer)

        initializeLines()
    }

    private fun initializeLines() {
        for (i in 0 until NotebookPaperView.LINE_COUNT) {
            createLineEditText(i)
        }
    }

    private fun createLineEditText(lineIndex: Int): LineEditText {
        val lineY = paperView.getLineY(lineIndex)
        val baseline = paperView.getTextBaseline(lineIndex)

        val editText = LineEditText(context).apply {
            this.lineIndex = lineIndex

            // Width: from margin to right edge, minus small padding
            val editWidth = (paperView.pageWidth - NotebookPaperView.MARGIN_LEFT - 12f).toInt()
            val editHeight = (NotebookPaperView.LINE_SPACING * 0.9f).toInt()

            layoutParams = LayoutParams(editWidth, editHeight).apply {
                leftMargin = (NotebookPaperView.MARGIN_LEFT + 8f).toInt()
                topMargin = (lineY - NotebookPaperView.LINE_SPACING * 0.45f).toInt()
            }

            currentPage.getLineAtIndex(lineIndex)?.let {
                setText(it.text)
                setSelection(it.text.length)
            }

            onLineActionListener = object : LineEditText.OnLineActionListener {
                override fun onNextLine(currentLine: Int) = moveToLine(currentLine + 1)
                override fun onPreviousLine(currentLine: Int) {
                    if (currentLine > 0) moveToLine(currentLine - 1)
                }
                override fun onLineTextChanged(lineIndex: Int, text: String) {
                    currentPage.addOrUpdateLine(lineIndex, text, text.length)
                    onPageChanged?.invoke(currentPage)
                }
                override fun onLineSelected(lineIndex: Int) {
                    if (isMultiSelectMode) toggleLineSelection(lineIndex)
                    else if (!isSettingActiveLine) setActiveLine(lineIndex)
                }
                override fun onLineLongPressed(lineIndex: Int) {
                    if (!isMultiSelectMode) {
                        isMultiSelectMode = true
                        selectedLines.clear()
                        toggleLineSelection(lineIndex)
                    }
                }
                override fun onLineOverflow(currentLine: Int, overflowText: String) {
                    val next = currentLine + 1
                    if (next < NotebookPaperView.LINE_COUNT) {
                        val nextEt = editTexts[next]
                        val existing = nextEt?.text?.toString() ?: ""
                        val newText = overflowText + existing
                        nextEt?.setText(newText)
                        nextEt?.setSelection(overflowText.length)
                        currentPage.addOrUpdateLine(next, newText, overflowText.length)
                        post { setActiveLine(next) }
                    }
                }
            }
        }

        textContainer.addView(editText)
        editTexts[lineIndex] = editText
        return editText
    }

    fun setActiveLine(lineIndex: Int) {
        if (lineIndex < 0 || lineIndex >= NotebookPaperView.LINE_COUNT) return
        if (isSettingActiveLine) return

        isSettingActiveLine = true

        if (activeLineIndex >= 0 && activeLineIndex != lineIndex) {
            editTexts[activeLineIndex]?.clearFocus()
        }

        activeLineIndex = lineIndex
        paperView.activeLineIndex = lineIndex

        val et = editTexts[lineIndex]
        et?.requestFocus()
        et?.setSelection(et.text?.length ?: 0)

        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            isSettingActiveLine = false
        }
    }

    fun moveToLine(lineIndex: Int) {
        if (lineIndex in 0 until NotebookPaperView.LINE_COUNT) setActiveLine(lineIndex)
    }

    private fun toggleLineSelection(lineIndex: Int) {
        if (lineIndex in selectedLines) selectedLines.remove(lineIndex)
        else selectedLines.add(lineIndex)
        paperView.selectedLineIndices = selectedLines
    }

    fun clearSelection() {
        isMultiSelectMode = false
        selectedLines.clear()
        paperView.selectedLineIndices = emptySet()
    }

    fun handleTapAt(x: Float, y: Float) {
        val idx = paperView.getLineIndexFromY(y)
        if (idx in 0 until NotebookPaperView.LINE_COUNT) setActiveLine(idx)
    }

    fun getSelectedLineIndices(): Set<Int> = selectedLines.toSet()
    fun deleteSelectedLines() { /* ... */ }
    fun copySelectedLines(): List<String> = emptyList()
}

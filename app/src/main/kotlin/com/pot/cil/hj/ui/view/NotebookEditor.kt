package com.pot.cil.hj.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.pot.cil.hj.data.NoteLine
import com.pot.cil.hj.data.NotebookPage

/**
 * The main editor composes the paper background with editable text lines.
 */
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
    var onLineCountChanged: ((Int) -> Unit)? = null

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
        onLineCountChanged?.invoke(NotebookPaperView.LINE_COUNT)
    }

    private fun createLineEditText(lineIndex: Int): LineEditText {
        val baselineY = paperView.getTextBaseline(lineIndex)
        val lineY = paperView.getLineY(lineIndex)

        val editText = LineEditText(context).apply {
            this.lineIndex = lineIndex

            layoutParams = LayoutParams(
                (paperView.pageWidth - NotebookPaperView.MARGIN_LEFT - 40f).toInt(),
                NotebookPaperView.LINE_SPACING.toInt()
            ).apply {
                leftMargin = (NotebookPaperView.MARGIN_LEFT + 15f).toInt()
                topMargin = (lineY - NotebookPaperView.LINE_SPACING / 2 + 5f).toInt()
            }

            currentPage.getLineAtIndex(lineIndex)?.let { noteLine ->
                setText(noteLine.text)
                setSelection(noteLine.text.length.coerceIn(0, noteLine.text.length))
            }

            onLineActionListener = object : LineEditText.OnLineActionListener {
                override fun onNextLine(currentLine: Int) {
                    moveToLine(currentLine + 1)
                }

                override fun onPreviousLine(currentLine: Int) {
                    if (currentLine > 0) moveToLine(currentLine - 1)
                }

                override fun onLineTextChanged(lineIndex: Int, text: String) {
                    currentPage.addOrUpdateLine(lineIndex, text, text.length)
                    onPageChanged?.invoke(currentPage)
                }

                override fun onLineSelected(lineIndex: Int) {
                    if (isMultiSelectMode) {
                        toggleLineSelection(lineIndex)
                    } else if (!isSettingActiveLine) {
                        setActiveLine(lineIndex)
                    }
                }

                override fun onLineLongPressed(lineIndex: Int) {
                    if (!isMultiSelectMode) {
                        isMultiSelectMode = true
                        selectedLines.clear()
                        toggleLineSelection(lineIndex)
                    }
                }

                override fun onLineOverflow(currentLine: Int, overflowText: String) {
                    val nextLine = currentLine + 1
                    if (nextLine < NotebookPaperView.LINE_COUNT) {
                        val nextEditText = editTexts[nextLine]
                        val existingText = nextEditText?.text?.toString() ?: ""
                        val newText = overflowText + existingText
                        nextEditText?.setText(newText)
                        nextEditText?.setSelection(overflowText.length)
                        currentPage.addOrUpdateLine(nextLine, newText, overflowText.length)
                        post { setActiveLine(nextLine) }
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

        val editText = editTexts[lineIndex]
        editText?.requestFocus()
        editText?.setSelection(editText.text?.length ?: 0)

        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            isSettingActiveLine = false
        }
    }

    fun moveToLine(lineIndex: Int) {
        if (lineIndex in 0 until NotebookPaperView.LINE_COUNT) {
            setActiveLine(lineIndex)
        }
    }

    private fun toggleLineSelection(lineIndex: Int) {
        if (lineIndex in selectedLines) {
            selectedLines.remove(lineIndex)
        } else {
            selectedLines.add(lineIndex)
        }
        paperView.selectedLineIndices = selectedLines
        editTexts[lineIndex]?.isSelected = lineIndex in selectedLines
    }

    fun clearSelection() {
        isMultiSelectMode = false
        selectedLines.clear()
        paperView.selectedLineIndices = emptySet()
    }

    fun getPage(): NotebookPage = currentPage

    fun loadPage(page: NotebookPage) {
        currentPage = page
        editTexts.values.forEach { it.setText("") }
        page.lines.forEach { line ->
            editTexts[line.lineIndex]?.setText(line.text)
        }
    }

    fun getLineEditText(lineIndex: Int): LineEditText? = editTexts[lineIndex]

    fun handleTapAt(x: Float, y: Float) {
        val lineIndex = paperView.getLineIndexFromY(y)
        if (lineIndex in 0 until NotebookPaperView.LINE_COUNT) {
            setActiveLine(lineIndex)
        }
    }

    fun deleteSelectedLines() {
        selectedLines.sortedDescending().forEach { index ->
            editTexts[index]?.setText("")
            currentPage.getLineAtIndex(index)?.let { it.text = "" }
        }
        clearSelection()
        onPageChanged?.invoke(currentPage)
    }

    fun copySelectedLines(): List<String> {
        return selectedLines.sorted().mapNotNull { index ->
            currentPage.getLineAtIndex(index)?.text?.takeIf { it.isNotBlank() }
        }
    }

    fun getSelectedLineIndices(): Set<Int> = selectedLines.toSet()
}

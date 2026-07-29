package com.pot.cil.hj.ui.view

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.pot.cil.hj.data.NoteLine
import com.pot.cil.hj.data.NotebookPage
import com.pot.cil.hj.ui.theme.NotebookColors

/**
 * The main editor composes the paper background with editable text lines.
 * Manages line creation, focus, selection, and coordinates with PanZoomLayout.
 */
class NotebookEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Child Views ──────────────────────────────────────────
    lateinit var paperView: NotebookPaperView
        private set

    private val textContainer: FrameLayout
    private val editTexts = mutableMapOf<Int, LineEditText>()

    // ── State ────────────────────────────────────────────────
    private var currentPage = NotebookPage()
    private var activeLineIndex: Int = -1
    private var selectedLines = mutableSetOf<Int>()
    private var isMultiSelectMode = false

    // ── Callbacks ────────────────────────────────────────────
    var onPageChanged: ((NotebookPage) -> Unit)? = null
    var onLineCountChanged: ((Int) -> Unit)? = null

    init {
        // Paper background
        paperView = NotebookPaperView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        addView(paperView)

        // Text container sits exactly on top of paper
        textContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        addView(textContainer)

        // Initialize with empty lines
        initializeLines()
    }

    private fun initializeLines() {
        // Pre-create EditTexts for all lines (optimizes scrolling)
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

            // Restore text if exists
            currentPage.getLineAtIndex(lineIndex)?.let { noteLine ->
                setText(noteLine.text)
                setSelection(noteLine.cursorPosition.coerceIn(0, noteLine.text.length))
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
                    } else {
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
            }
        }

        textContainer.addView(editText)
        editTexts[lineIndex] = editText
        return editText
    }

    // ── Line Management ──────────────────────────────────────
    fun setActiveLine(lineIndex: Int) {
        if (lineIndex < 0 || lineIndex >= NotebookPaperView.LINE_COUNT) return

        // Clear previous active
        if (activeLineIndex >= 0) {
            editTexts[activeLineIndex]?.clearFocus()
        }

        activeLineIndex = lineIndex
        paperView.activeLineIndex = lineIndex

        val editText = editTexts[lineIndex]
        editText?.requestFocus()
        editText?.setSelection(editText.text?.length ?: 0)

        // Show keyboard
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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

    // ── Content API ──────────────────────────────────────────
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

    // ── Selection Actions ────────────────────────────────────
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

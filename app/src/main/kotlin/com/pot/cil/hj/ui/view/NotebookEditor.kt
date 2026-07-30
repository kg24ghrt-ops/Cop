package com.pot.cil.hj.ui.view

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.util.AttributeSet
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import com.pot.cil.hj.data.NotebookPage
import java.io.File
import java.io.FileOutputStream

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
    
    fun deleteSelectedLines() {
        // Implement deletion logic as needed
        // For now, just clear text on selected lines
        selectedLines.forEach { idx ->
            editTexts[idx]?.setText("")
            currentPage.addOrUpdateLine(idx, "", 0)
        }
        clearSelection()
    }

    fun copySelectedLines(): List<String> = emptyList()

    // ============ NEW EXPORT METHOD ============
    fun exportToPng() {
        val paperWidth = paperView.width
        val paperHeight = paperView.height
        if (paperWidth == 0 || paperHeight == 0) return

        val bitmap = Bitmap.createBitmap(paperWidth, paperHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw paper background and ruled lines
        paperView.draw(canvas)

        // Draw each line's text at its correct position
        // Use drawingCache for each edit text; it's acceptable for small views.
        for ((_, editText) in editTexts) {
            val x = editText.left.toFloat()
            val y = editText.top.toFloat()
            canvas.save()
            canvas.translate(x, y)
            // Enable drawing cache and draw it
            editText.isDrawingCacheEnabled = true
            val textBitmap = editText.drawingCache
            if (textBitmap != null && !textBitmap.isRecycled) {
                canvas.drawBitmap(textBitmap, 0f, 0f, null)
            }
            editText.isDrawingCacheEnabled = false
            canvas.restore()
        }

        // Save to external Pictures folder
        val filename = "notebook_${System.currentTimeMillis()}.png"
        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        if (outputDir == null) {
            // Fallback: internal cache
            context.toast("Export failed: storage not available")
            return
        }
        outputDir.mkdirs()
        val file = File(outputDir, filename)

        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            context.toast("Error saving file: ${e.message}")
            return
        } finally {
            bitmap.recycle()
        }

        // Share via FileProvider
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Notebook"))
    }

    // Helper extension for Toast
    private fun Context.toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
package com.pot.cil.hj

import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: MyGLSurfaceView
    private lateinit var fakeEditText: FakeEditText
    private lateinit var lineNumberText: TextView
    private lateinit var totalLinesText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this)

        fakeEditText = FakeEditText(this)

        glSurfaceView = MyGLSurfaceView(this).apply {
            setFakeEditText(fakeEditText)
        }

        val controlPanel = createControlPanel()

        rootLayout.addView(fakeEditText)
        rootLayout.addView(glSurfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootLayout.addView(controlPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        })

        setContentView(rootLayout)

        glSurfaceView.post {
            glSurfaceView.updateRenderedText("Tap a line or use the buttons below...")
            updateLineInfo()
        }
    }

    private fun createControlPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            setPadding(24, 16, 24, 16)

            val lineInfoLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            lineNumberText = TextView(this@MainActivity).apply {
                text = "Line: 4"
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                textSize = 18f
            }

            totalLinesText = TextView(this@MainActivity).apply {
                text = "of 32"
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                textSize = 14f
                alpha = 0.7f
            }

            lineInfoLayout.addView(lineNumberText)
            lineInfoLayout.addView(totalLinesText)

            val buttonLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val upButton = Button(this@MainActivity).apply {
                text = "▲"
                layoutParams = LinearLayout.LayoutParams(
                    120,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                setOnClickListener {
                    glSurfaceView.moveLineUp()
                    updateLineInfo()
                }
            }

            val downButton = Button(this@MainActivity).apply {
                text = "▼"
                layoutParams = LinearLayout.LayoutParams(
                    120,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    glSurfaceView.moveLineDown()
                    updateLineInfo()
                }
            }

            val clearButton = Button(this@MainActivity).apply {
                text = "✕"
                layoutParams = LinearLayout.LayoutParams(
                    80,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 16 }
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.transparent))
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                setOnClickListener {
                    glSurfaceView.clearRenderedText()
                    fakeEditText.clearText()
                    updateLineInfo()
                }
            }

            val keyboardButton = Button(this@MainActivity).apply {
                text = "⌨"
                layoutParams = LinearLayout.LayoutParams(
                    80,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8 }
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.transparent))
                setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                setOnClickListener {
                    if (fakeEditText.hasFocus()) {
                        glSurfaceView.hideKeyboard()
                        fakeEditText.clearFocus()
                    } else {
                        glSurfaceView.showKeyboard()
                    }
                }
            }

            buttonLayout.addView(upButton)
            buttonLayout.addView(downButton)
            buttonLayout.addView(clearButton)
            buttonLayout.addView(keyboardButton)

            addView(lineInfoLayout)
            addView(buttonLayout)
        }
    }

    private fun updateLineInfo() {
        val currentLine = glSurfaceView.getCurrentLine()
        val totalLines = glSurfaceView.getTotalLines()
        lineNumberText.text = "Line: ${currentLine + 1}"
        totalLinesText.text = "of $totalLines"
    }

    override fun onBackPressed() {
        if (fakeEditText.hasFocus()) {
            glSurfaceView.hideKeyboard()
            fakeEditText.clearFocus()
        } else {
            super.onBackPressed()
        }
    }
}
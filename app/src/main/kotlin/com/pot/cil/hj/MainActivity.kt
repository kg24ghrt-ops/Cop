package com.pot.cil.hj

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.FrameLayout
import android.view.ViewGroup
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var notebookView: NotebookView
    private lateinit var fakeEditText: FakeEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Invisible EditText for keyboard input
        fakeEditText = FakeEditText(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }

        // Main notebook view
        notebookView = NotebookView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setFakeEditText(fakeEditText)
        }

        val root = FrameLayout(this).apply {
            addView(notebookView)
            addView(fakeEditText)
        }

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        notebookView.onResume()
    }

    override fun onPause() {
        super.onPause()
        notebookView.onPause()
    }
}
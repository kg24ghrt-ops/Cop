package com.pot.cil.hj

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback

class MainActivity : ComponentActivity() {

    private lateinit var glSurfaceView: MyGLSurfaceView
    private lateinit var fakeEditText: FakeEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this)

        // 1. Create invisible FakeEditText
        fakeEditText = FakeEditText(this)

        // 2. Create GLSurfaceView
        glSurfaceView = MyGLSurfaceView(this).apply {
            setFakeEditText(fakeEditText)
        }

        // 3. Add views
        rootLayout.addView(fakeEditText)
        rootLayout.addView(glSurfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(rootLayout)

        // Set initial hint text
        glSurfaceView.post {
            glSurfaceView.updateRenderedText("Tap the paper to start typing...")
        }

        // Modern back button handling using OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(this) {
            if (fakeEditText.hasFocus()) {
                glSurfaceView.hideKeyboard()
                fakeEditText.clearFocus()
            } else {
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // GLSurfaceView handles its own pause
    }

    override fun onResume() {
        super.onResume()
        // GLSurfaceView handles its own resume
    }
}
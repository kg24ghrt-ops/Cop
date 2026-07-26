package com.pot.cil.hj

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var glSurfaceView: MyGLSurfaceView
    private lateinit var fakeEditText: FakeEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a root FrameLayout
        val rootLayout = FrameLayout(this)

        // 1. Create the invisible FakeEditText
        fakeEditText = FakeEditText(this)

        // 2. Create the GLSurfaceView
        glSurfaceView = MyGLSurfaceView(this).apply {
            setFakeEditText(fakeEditText)
        }

        // 3. Add both views to the root layout
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
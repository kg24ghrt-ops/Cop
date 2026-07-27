package com.pot.cil.hj

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: MyGLSurfaceView
    private lateinit var fakeEditText: FakeEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this)

        fakeEditText = FakeEditText(this)

        glSurfaceView = MyGLSurfaceView(this).apply {
            setFakeEditText(fakeEditText)
        }

        rootLayout.addView(fakeEditText)
        rootLayout.addView(glSurfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(rootLayout)

        glSurfaceView.post {
            glSurfaceView.updateRenderedText("Tap a line to select it...")
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
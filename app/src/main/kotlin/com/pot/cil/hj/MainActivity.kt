package com.pot.cil.hj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InkEngineView()
                }
            }
        }
    }

    @Composable
    fun InkEngineView() {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MyGLSurfaceView(context).apply {
                    // Post to the view's message queue to ensure it's fully laid out
                    post {
                        val sampleText = TextOverlay(
                            text = "Hello, Notebook!",
                            lineNumber = 3,
                            textSize = 40f,
                            color = android.graphics.Color.BLACK,
                            xOffset = 20f,
                            yOffset = 20f
                        )
                        setTextOverlay(sampleText)
                    }
                }
            },
            update = { view ->
                // Called when the view is updated (e.g., recomposition)
                // You can optionally update the text here based on state
            }
        )
    }
}
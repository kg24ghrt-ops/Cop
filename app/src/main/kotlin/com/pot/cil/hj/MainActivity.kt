package com.pot.cil.hj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    private lateinit var glSurfaceView: MyGLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            androidx.compose.material3.MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InkEngineView()
                }
            }
        }

        // Wait for the view to be ready and set a sample text
        glSurfaceView.post {
            val sampleText = TextOverlay(
                text = "Hello, Notebook!",
                lineNumber = 3,
                textSize = 40f,
                color = android.graphics.Color.BLACK,
                xOffset = 20f,
                yOffset = 20f
            )
            glSurfaceView.setTextOverlay(sampleText)   // ✅ Use public method
        }
    }

    @Composable
    fun InkEngineView() {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MyGLSurfaceView(context).also {
                    glSurfaceView = it
                }
            },
            update = { view ->
                // Optional: update when state changes
            }
        )
    }
}
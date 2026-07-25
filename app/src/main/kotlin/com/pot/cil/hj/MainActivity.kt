package com.pot.cil.hj

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context  // explicit import for Context

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            androidx.compose.material3.MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InkEngineView()
                }
            }
        }
    }
}

@Composable
fun InkEngineView() {
    // Explicitly specify type arguments to help inference
    AndroidView<MyGLSurfaceView>(
        modifier = Modifier.fillMaxSize(),
        factory = { context: Context ->
            MyGLSurfaceView(context).apply {
                // optional setup
            }
        },
        update = { view: MyGLSurfaceView ->
            // called when the view needs to update (e.g., recomposition)
        }
    )
}
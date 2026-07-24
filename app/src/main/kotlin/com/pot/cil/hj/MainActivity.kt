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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Use the theme defined in themes.xml
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
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MyGLSurfaceView(context).apply {
                // You can pass a reference of this view to a ViewModel if needed
            }
        },
        update = { view ->
            // Update logic here when state changes
        }
    )
}
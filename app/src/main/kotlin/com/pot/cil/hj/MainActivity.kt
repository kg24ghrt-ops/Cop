package com.pot.cil.hj

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pot.cil.hj.databinding.ActivityMainBinding
import com.pot.cil.hj.ui.view.NotebookPaperView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPanZoom()
        setupToolbar()
        setupEditor()
        setupGestures()
    }

    private fun setupPanZoom() {
        binding.panZoomLayout.onTransformChanged = { scale, _, _ ->
            val percentage = (scale * 100).toInt()
            binding.tvZoomLevel.text = "$percentage%"
        }

        binding.panZoomLayout.onTapAtLocation = { x, y ->
            binding.notebookEditor.handleTapAt(x, y)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.apply {
            onZoomIn = {
                val currentScale = binding.panZoomLayout.getCurrentScale()
                binding.panZoomLayout.zoomToPoint(
                    (currentScale * 1.3f).coerceAtMost(4.0f),
                    binding.panZoomLayout.width / 2f,
                    binding.panZoomLayout.height / 2f
                )
            }

            onZoomOut = {
                val currentScale = binding.panZoomLayout.getCurrentScale()
                binding.panZoomLayout.zoomToPoint(
                    (currentScale / 1.3f).coerceAtLeast(0.5f),
                    binding.panZoomLayout.width / 2f,
                    binding.panZoomLayout.height / 2f
                )
            }

            onResetZoom = {
                binding.panZoomLayout.resetTransform()
            }

            onSelectAll = {
                val allIndices = (0 until NotebookPaperView.LINE_COUNT).toSet()
                binding.notebookEditor.paperView.selectedLineIndices = allIndices
                binding.toolbar.setSelectionMode(true, allIndices.size)
            }

            onClearSelection = {
                binding.notebookEditor.clearSelection()
                binding.toolbar.setSelectionMode(false)
            }

            onDeleteSelected = {
                binding.notebookEditor.deleteSelectedLines()
                binding.toolbar.setSelectionMode(false)
                Toast.makeText(this@MainActivity, "Deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupEditor() {
        binding.notebookEditor.onPageChanged = { page ->
            // Auto-save or sync could happen here
        }

        binding.notebookEditor.onLineCountChanged = { count ->
            // Update any UI showing line count
        }
    }

    private fun setupGestures() {
        // Hide hint after a few seconds
        lifecycleScope.launch {
            delay(4000)
            binding.tvHint.animate()
                .alpha(0f)
                .setDuration(500)
                .withEndAction { binding.tvHint.visibility = View.GONE }
                .start()
        }
    }

    override fun onBackPressed() {
        if (binding.notebookEditor.getSelectedLineIndices().isNotEmpty()) {
            binding.notebookEditor.clearSelection()
            binding.toolbar.setSelectionMode(false)
        } else {
            super.onBackPressed()
        }
    }
}

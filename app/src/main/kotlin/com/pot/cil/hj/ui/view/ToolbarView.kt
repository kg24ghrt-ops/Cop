package com.pot.cil.hj.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.pot.cil.hj.databinding.ViewToolbarBinding

/**
 * Floating toolbar for notebook actions (zoom, selection, etc.)
 */
class ToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewToolbarBinding

    var onZoomIn: (() -> Unit)? = null
    var onZoomOut: (() -> Unit)? = null
    var onResetZoom: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null
    var onClearSelection: (() -> Unit)? = null
    var onDeleteSelected: (() -> Unit)? = null
    var onExport: (() -> Unit)? = null   // NEW

    init {
        orientation = HORIZONTAL
        binding = ViewToolbarBinding.inflate(LayoutInflater.from(context), this, true)

        binding.btnZoomIn.setOnClickListener { onZoomIn?.invoke() }
        binding.btnZoomOut.setOnClickListener { onZoomOut?.invoke() }
        binding.btnResetZoom.setOnClickListener { onResetZoom?.invoke() }
        binding.btnSelectAll.setOnClickListener { onSelectAll?.invoke() }
        binding.btnClear.setOnClickListener { onClearSelection?.invoke() }
        binding.btnDelete.setOnClickListener { onDeleteSelected?.invoke() }
        binding.btnExport.setOnClickListener { onExport?.invoke() }   // NEW
    }

    fun setSelectionMode(active: Boolean, count: Int = 0) {
        binding.btnSelectAll.visibility = if (active) GONE else VISIBLE
        binding.btnClear.visibility = if (active) VISIBLE else GONE
        binding.btnDelete.visibility = if (active) VISIBLE else GONE
        binding.tvSelectionCount.text = if (active) "$count selected" else ""
        binding.tvSelectionCount.visibility = if (active) VISIBLE else GONE
    }
}
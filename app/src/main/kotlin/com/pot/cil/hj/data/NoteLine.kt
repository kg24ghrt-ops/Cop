package com.pot.cil.hj.data

/**
 * Data model for a single line of text on the notebook.
 */
data class NoteLine(
    val id: Int,
    val lineIndex: Int,           // Which ruled line this sits on
    var text: String = "",
    var isSelected: Boolean = false,
    var cursorPosition: Int = 0
)

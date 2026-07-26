package com.pot.cil.hj

data class TextOverlay(
    val text: String,
    val lineNumber: Int,          // 0 = first line, 1 = second, etc.
    val textSize: Float = 40f,
    val color: Int = android.graphics.Color.BLACK,
    val xOffset: Float = 20f,     // left margin in pixels
    val yOffset: Float = 20f      // baseline adjustment
)
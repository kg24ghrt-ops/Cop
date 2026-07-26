package com.pot.cil.hj

data class TextOverlay(
    val text: String,
    val lineNumber: Int,          // 0-based index
    val textSize: Float = 40f,
    val color: Int = android.graphics.Color.parseColor("#003B73"), // Ballpoint blue ink

    val xOffset: Float = 40f,
    val yOffset: Float = 30f
)
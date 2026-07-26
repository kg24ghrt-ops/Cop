package com.pot.cil.hj

data class TextOverlay(
    val text: String,
    val lineNumber: Int,
    val textSize: Float = 40f,
    val color: Int = android.graphics.Color.BLACK,
    val xOffset: Float = 20f,
    val yOffset: Float = 20f
)
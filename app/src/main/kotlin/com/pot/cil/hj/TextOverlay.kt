package com.pot.cil.hj

import android.graphics.Color

data class TextOverlay(
    val text: String,
    val lineNumber: Int,           // 0 = first line
    val textSize: Float = 40f,
    val color: Int = Color.BLACK,
    val xOffset: Float = 40f,      // Left margin + small padding
    val yOffset: Float = 20f       // Baseline adjustment
)
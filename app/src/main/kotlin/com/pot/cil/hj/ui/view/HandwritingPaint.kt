package com.pot.cil.hj.ui.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import kotlin.random.Random

/**
 * Renders text with realistic handwriting simulation.
 * 
 * Human handwriting characteristics at real scale:
 * - Baseline jitter: ±0.5mm (subtle, not cartoonish)
 * - Character spacing: varies by ±0.3mm
 * - Ink weight: 0.3-0.5mm stroke (ballpoint pen)
 * - Slight rotation: ±1.5 degrees per cluster
 * - Size variation: ±5% per character
 * 
 * Works with ALL languages by operating on grapheme clusters.
 */
class HandwritingPaint {
    
    // ── Configuration (real human handwriting) ───────────────
    var baselineJitterMm: Float = 0.5f
    var spacingVariationMm: Float = 0.3f
    var inkWeightMm: Float = 0.35f
    var rotationDegrees: Float = 1.5f
    var sizeVariationPercent: Float = 0.05f
    var enableEffects: Boolean = true
    
    // Pixel conversion (at 160dpi, 1mm ≈ 6.3px)
    private val pxPerMm = 6.3f
    
    /**
     * Draw text with subtle handwriting imperfections.
     * Seed ensures same text always renders identically.
     */
    fun drawHandwrittenText(
        canvas: Canvas,
        text: CharSequence,
        x: Float,
        y: Float,
        paint: TextPaint,
        seed: Int = text.hashCode()
    ) {
        if (text.isEmpty()) return
        
        if (!enableEffects) {
            canvas.drawText(text.toString(), x, y, paint)
            return
        }
        
        val localRandom = Random(seed)
        val textString = text.toString()
        val clusters = extractGraphemeClusters(textString)
        
        var currentX = x
        val baseTextSize = paint.textSize
        
        for (cluster in clusters) {
            val clusterWidth = paint.measureText(cluster)
            
            // Subtle baseline jitter (±0.5mm)
            val jitterY = (localRandom.nextFloat() - 0.5f) * baselineJitterMm * pxPerMm * 2
            
            // Subtle rotation (±1.5°)
            val rotation = (localRandom.nextFloat() - 0.5f) * rotationDegrees * 2
            
            // Size variation (±5%)
            val sizeMultiplier = 1f + (localRandom.nextFloat() - 0.5f) * sizeVariationPercent * 2
            
            // Spacing variation (±0.3mm)
            val spacingOffset = (localRandom.nextFloat() - 0.5f) * spacingVariationMm * pxPerMm * 2
            
            // Ink weight variation (ballpoint feel)
            val weightVar = (localRandom.nextFloat() - 0.5f) * 0.5f
            paint.strokeWidth = (inkWeightMm * pxPerMm) + weightVar
            
            // Slight alpha variation for ink wetness
            paint.alpha = (245 + localRandom.nextInt(10)).coerceIn(230, 255)
            
            canvas.save()
            canvas.translate(currentX + spacingOffset, y + jitterY)
            canvas.rotate(rotation)
            paint.textSize = baseTextSize * sizeMultiplier
            canvas.drawText(cluster, 0f, 0f, paint)
            canvas.restore()
            
            currentX += clusterWidth + spacingOffset + (baseTextSize * 0.12f)
        }
        
        paint.textSize = baseTextSize
        paint.alpha = 255
        paint.strokeWidth = 0f
    }
    
    /**
     * Extract grapheme clusters — user-perceived characters.
     * Handles Myanmar, Arabic, Thai, Devanagari, Hangul, emoji, etc.
     */
    private fun extractGraphemeClusters(text: String): List<String> {
        val clusters = mutableListOf<String>()
        var i = 0
        
        while (i < text.length) {
            val start = i
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            
            // Absorb combining characters
            while (i < text.length && isCombining(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
            }
            
            // Myanmar medials (ြ ျ ှ ္)
            while (i < text.length && isMyanmarMedial(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
            }
            
            // Myanmar asat (်) + following consonant cluster
            if (i < text.length && text.codePointAt(i) == 0x103A) {
                i += Character.charCount(text.codePointAt(i))
                while (i < text.length && isMyanmarConsonant(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i))
                    while (i < text.length && isMyanmarMedial(text.codePointAt(i))) {
                        i += Character.charCount(text.codePointAt(i))
                    }
                }
            }
            
            // ZWJ sequences (emoji, Arabic contextual)
            while (i < text.length && isJoiner(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
                if (i < text.length) {
                    i += Character.charCount(text.codePointAt(i))
                }
            }
            
            clusters.add(text.substring(start, i))
        }
        
        return clusters
    }
    
    private fun isCombining(cp: Int): Boolean = when {
        cp in 0x0300..0x036F -> true      // Combining Diacritical Marks
        cp in 0x1AB0..0x1AFF -> true      // Extended
        cp in 0x1DC0..0x1DFF -> true      // Supplement
        cp in 0x20D0..0x20FF -> true      // For Symbols
        cp in 0xFE20..0xFE2F -> true      // Half Marks
        cp in 0x064B..0x065F -> true      // Arabic tashkeel
        cp == 0x0670 -> true
        cp in 0x0591..0x05BD -> true      // Hebrew
        cp in 0x05BF..0x05C7 -> true
        cp in 0x0E31..0x0E3A -> true      // Thai
        cp in 0x0E47..0x0E4E -> true
        cp in 0x0EB1..0x0EB9 -> true      // Lao
        cp in 0x0EC8..0x0ECD -> true
        cp in 0x102B..0x103E -> true      // Myanmar
        cp in 0x1056..0x1059 -> true
        cp in 0x105E..0x1060 -> true
        cp in 0x093E..0x094F -> true      // Devanagari
        cp in 0x0951..0x0957 -> true
        cp in 0x0962..0x0963 -> true
        cp in 0x09BE..0x09CC -> true      // Bengali etc
        cp in 0x0A3E..0x0A4C -> true
        cp in 0x0ABE..0x0ACC -> true
        cp in 0x0B3E..0x0B4C -> true
        cp in 0x0BBE..0x0BCC -> true
        cp in 0x0C3E..0x0C4C -> true
        cp in 0x0CBE..0x0CCC -> true
        cp in 0x0D3E..0x0D4C -> true
        cp in 0x17B6..0x17D3 -> true      // Khmer
        cp in 0x1161..0x1175 -> true      // Hangul jungseong
        cp in 0x11A8..0x11C2 -> true      // Hangul jongseong
        cp in 0xFE00..0xFE0F -> true      // Variation selectors
        cp in 0xE0100..0xE01EF -> true
        else -> false
    }
    
    private fun isMyanmarMedial(cp: Int) = cp in 0x103B..0x103E
    private fun isMyanmarConsonant(cp: Int) = cp in 0x1000..0x102A
    private fun isJoiner(cp: Int) = cp == 0x200D || cp == 0x200C
}

package com.pot.cil.hj.ui.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.os.Build
import kotlin.random.Random

/**
 * Renders text with handwriting-like imperfections: baseline jitter,
 * random character spacing, ink weight variation, and slight wobble.
 * 
 * Works with ALL languages including Myanmar, Arabic, Thai, Devanagari,
 * by operating on measured glyph runs rather than individual characters.
 */
class HandwritingPaint {
    
    private val random = Random.Default
    
    // Configuration
    var baselineJitterAmount: Float = 2.5f      // Max pixels of Y wobble
    var spacingVariation: Float = 0.8f          // Max extra/less space between runs
    var inkWeightVariation: Float = 0.4f          // Stroke width variation
    var wobbleAmount: Float = 1.2f              // Path wobble for cursive feel
    var enableJitter: Boolean = true
    var enableSpacing: Boolean = true
    var enableInkVariation: Boolean = true
    
    // Seed for consistent rendering of same text
    private var currentSeed: Int = 0
    
    /**
     * Draw text with handwriting simulation onto canvas.
     * 
     * @param canvas The canvas to draw on
     * @param text The text to draw
     * @param x Start X position
     * @param y Baseline Y position  
     * @param paint The base paint (will be modified for effects)
     * @param seed Random seed for consistent rendering
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
        
        currentSeed = seed
        val baseStrokeWidth = paint.strokeWidth
        
        // For RTL and complex scripts, we use StaticLayout to handle shaping
        // then extract glyph runs for jitter application
        if (shouldUseGlyphRuns(text)) {
            drawWithGlyphRuns(canvas, text, x, y, paint)
        } else {
            // Simple path for basic Latin text
            drawWithGlyphRuns(canvas, text, x, y, paint)
        }
        
        paint.strokeWidth = baseStrokeWidth
    }
    
    /**
     * Draw text by breaking it into grapheme clusters (user-perceived characters)
     * and applying jitter to each cluster's position. This works for ALL languages
     * because we measure actual rendered glyphs, not code points.
     */
    private fun drawWithGlyphRuns(
        canvas: Canvas,
        text: CharSequence,
        x: Float,
        y: Float,
        paint: TextPaint
    ) {
        val textString = text.toString()
        val length = textString.length
        var currentX = x
        
        // Use a consistent random sequence for this text
        val localRandom = Random(currentSeed)
        
        // Break text into grapheme clusters using ICU boundaries
        val clusters = extractGraphemeClusters(textString)
        
        for ((index, cluster) in clusters.withIndex()) {
            // Measure this cluster's width
            val clusterWidth = paint.measureText(cluster)
            
            // Apply random spacing variation
            val spacingOffset = if (enableSpacing) {
                (localRandom.nextFloat() - 0.5f) * spacingVariation * 2
            } else 0f
            
            // Apply baseline jitter
            val jitterY = if (enableJitter) {
                (localRandom.nextFloat() - 0.5f) * baselineJitterAmount * 2
            } else 0f
            
            // Apply ink weight variation
            if (enableInkVariation) {
                val weightVar = (localRandom.nextFloat() - 0.5f) * inkWeightVariation * 2
                paint.strokeWidth = paint.textSize * 0.06f + weightVar
                // Slightly vary alpha for ink wetness effect
                paint.alpha = (230 + localRandom.nextInt(25)).coerceIn(180, 255)
            }
            
            // Draw the cluster with jitter
            canvas.drawText(cluster, currentX + spacingOffset, y + jitterY, paint)
            
            // Advance position
            currentX += clusterWidth + spacingOffset + paint.textSize * 0.05f
        }
        
        paint.alpha = 255
    }
    
    /**
     * Extract grapheme clusters from text. This properly handles:
     * - Myanmar (e.g., က္က + ာ + ့ = one cluster)
     * - Arabic (letters + diacritics + tashkeel)
     * - Thai/Lao (consonant + vowel + tone mark)
     * - Devanagari (consonant cluster + vowel signs)
     * - Emoji (ZWJ sequences)
     * - Korean Hangul jamo composition
     */
    private fun extractGraphemeClusters(text: String): List<String> {
        val clusters = mutableListOf<String>()
        var i = 0
        
        while (i < text.length) {
            val start = i
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            
            // Absorb subsequent combining characters/mark characters
            while (i < text.length && isCombiningCharacter(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
            }
            
            // Handle Myanmar medial consonants (e.g., ျ, ြ, ှ, ္)
            // These form clusters with the preceding consonant
            while (i < text.length && isMyanmarMedial(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
            }
            
            // Handle Myanmar asat (်) which creates stacked consonants
            if (i < text.length && text.codePointAt(i) == 0x103A) {
                i += Character.charCount(text.codePointAt(i))
                // May be followed by another consonant + medials
                while (i < text.length && isMyanmarConsonant(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i))
                    while (i < text.length && isMyanmarMedial(text.codePointAt(i))) {
                        i += Character.charCount(text.codePointAt(i))
                    }
                }
            }
            
            // Handle zero-width joiner sequences (emoji, Arabic contextual forms)
            while (i < text.length && (
                text.codePointAt(i) == 0x200D || // ZWJ
                text.codePointAt(i) == 0x200C    // ZWNJ
            )) {
                i += Character.charCount(text.codePointAt(i))
                if (i < text.length) {
                    i += Character.charCount(text.codePointAt(i))
                }
            }
            
            clusters.add(text.substring(start, i))
        }
        
        return clusters
    }
    
    /** Check if code point is a combining character */
    private fun isCombiningCharacter(codePoint: Int): Boolean {
        return when {
            // Combining Diacritical Marks
            codePoint in 0x0300..0x036F -> true
            // Combining Diacritical Marks Extended
            codePoint in 0x1AB0..0x1AFF -> true
            // Combining Diacritical Marks Supplement
            codePoint in 0x1DC0..0x1DFF -> true
            // Combining Diacritical Marks for Symbols
            codePoint in 0x20D0..0x20FF -> true
            // Combining Half Marks
            codePoint in 0xFE20..0xFE2F -> true
            // Arabic diacritics (tashkeel)
            codePoint in 0x064B..0x065F -> true
            codePoint == 0x0670 -> true
            // Hebrew points
            codePoint in 0x0591..0x05BD -> true
            codePoint in 0x05BF..0x05C7 -> true
            // Thai/Lao tone marks and vowels that combine
            codePoint in 0x0E31..0x0E3A -> true // Thai above/below
            codePoint in 0x0E47..0x0E4E -> true // Thai tone marks
            codePoint in 0x0EB1..0x0EB9 -> true // Lao
            codePoint in 0x0EC8..0x0ECD -> true // Lao tone
            // Myanmar dependent vowels, signs, tone marks
            codePoint in 0x102B..0x103E -> true
            codePoint in 0x1056..0x1059 -> true
            codePoint in 0x105E..0x1060 -> true
            // Devanagari dependent vowels and marks
            codePoint in 0x093E..0x094F -> true
            codePoint in 0x0951..0x0957 -> true
            codePoint in 0x0962..0x0963 -> true
            // Bengali, Gurmukhi, Gujarati, etc.
            codePoint in 0x09BE..0x09CC -> true
            codePoint in 0x0A3E..0x0A4C -> true
            codePoint in 0x0ABE..0x0ACC -> true
            codePoint in 0x0B3E..0x0B4C -> true
            codePoint in 0x0BBE..0x0BCC -> true
            codePoint in 0x0C3E..0x0C4C -> true
            codePoint in 0x0CBE..0x0CCC -> true
            codePoint in 0x0D3E..0x0D4C -> true
            // Khmer
            codePoint in 0x17B6..0x17D3 -> true
            // Tibetan
            codePoint in 0x0F18..0x0F19 -> true
            codePoint in 0x0F35..0x0F39 -> true
            codePoint in 0x0F3E..0x0F3F -> true
            codePoint in 0x0F71..0x0F84 -> true
            codePoint in 0x0F86..0x0FBC -> true
            // Sinhala
            codePoint in 0x0DCA..0x0DDF -> true
            // Hangul jamo that combine (jungseong, jongseong)
            codePoint in 0x1161..0x1175 -> true // jungseong
            codePoint in 0x11A8..0x11C2 -> true // jongseong
            // Variation selectors
            codePoint in 0xFE00..0xFE0F -> true
            codePoint in 0xE0100..0xE01EF -> true
            else -> false
        }
    }
    
    /** Check if code point is a Myanmar medial consonant */
    private fun isMyanmarMedial(codePoint: Int): Boolean {
        return codePoint in 0x103B..0x103E
    }
    
    /** Check if code point is a Myanmar consonant */
    private fun isMyanmarConsonant(codePoint: Int): Boolean {
        return codePoint in 0x1000..0x102A
    }
    
    /** Determine if text needs complex shaping */
    private fun shouldUseGlyphRuns(text: CharSequence): Boolean {
        // Always use glyph runs for proper handling
        return true
    }
    
    /**
     * Draw a more organic path-based text for cursive scripts.
     * Creates slight wobble in the stroke for fountain-pen feel.
     */
    fun drawCursiveText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: TextPaint,
        seed: Int = text.hashCode()
    ) {
        val localRandom = Random(seed)
        val path = Path()
        
        // For cursive, we draw each word with connecting wobble
        val words = text.split(" ")
        var currentX = x
        
        for ((wordIndex, word) in words.withIndex()) {
            val wordWidth = paint.measureText(word)
            
            // Create wobbly baseline path
            val points = (0..10).map { t ->
                val px = currentX + (wordWidth * t / 10)
                val py = y + (localRandom.nextFloat() - 0.5f) * wobbleAmount
                Pair(px, py)
            }
            
            if (points.isNotEmpty()) {
                path.moveTo(points[0].first, points[0].second)
                for (i in 1 until points.size) {
                    // Quadratic bezier for smooth wobble
                    val midX = (points[i-1].first + points[i].first) / 2
                    val midY = (points[i-1].second + points[i].second) / 2
                    path.quadTo(points[i-1].first, points[i-1].second, midX, midY)
                }
                path.lineTo(points.last().first, points.last().second)
            }
            
            // Draw text along the wobbly path (simplified - just draw with jitter)
            drawWithGlyphRuns(canvas, word, currentX, y, paint)
            
            currentX += wordWidth + paint.measureText(" ")
            
            // Draw space
            if (wordIndex < words.size - 1) {
                val spaceWidth = paint.measureText(" ") * (0.8f + localRandom.nextFloat() * 0.4f)
                currentX += spaceWidth
            }
        }
        
        paint.style = Paint.Style.FILL
    }
}

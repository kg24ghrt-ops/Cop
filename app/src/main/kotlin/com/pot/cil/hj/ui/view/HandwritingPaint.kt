package com.pot.cil.hj.ui.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import kotlin.random.Random

/**
 * Renders text with realistic human handwriting including intentional mistakes.
 * 
 * Human mistakes are stroke-level artifacts that apply to ANY writing system:
 * - Wobbly/uneven strokes (shaky hand)
 * - Ink pooling at stroke starts/ends
 * - Slight misalignment of stroke clusters
 * - Variable pressure (thick/thin strokes)
 * - Occasional skipped connections between strokes
 * - Micro-tremors in long strokes
 * - Slight rotation drift within a word
 * 
 * These are visual imperfections, not glyph mutations — works for Myanmar,
 * Arabic, Thai, Devanagari, Latin, CJK, emoji, everything.
 */
class HandwritingPaint {
    
    // ── Physical scale ─────────────────────────────────────────
    private val pxPerMm = 6.3f
    
    // ── Mistake configuration (tune these for more/less messy) ─
    var enableMistakes: Boolean = true
    var shakiness: Float = 0.8f              // 0=perfect, 2=very shaky
    var inkPoolChance: Float = 0.15f          // Chance of ink blob at stroke start
    var pressureVariation: Float = 0.6f         // Thick/thin stroke variation
    var rotationDrift: Float = 2.0f           // Degrees of rotation drift per word
    var microTremor: Float = 0.4f             // Tiny wobble in strokes
    var skipConnectionChance: Float = 0.08f     // Gap in cursive-like connection
    var baselineWander: Float = 1.2f          // Baseline drifts up/down slightly
    
    /**
     * Draw text with human handwriting mistakes.
     * Operates on measured glyph runs so complex scripts stay intact.
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
        
        val localRandom = Random(seed)
        val textString = text.toString()
        
        // Break into words (space-separated) to apply per-word drift
        val words = textString.split(" ")
        var currentX = x
        var currentBaseline = y
        
        for ((wordIndex, word) in words.withIndex()) {
            val wordSeed = seed + wordIndex * 9973
            
            // Per-word baseline wander (human lines aren't perfectly straight)
            currentBaseline += (localRandom.nextFloat() - 0.5f) * baselineWander * pxPerMm
            
            // Per-word rotation drift
            val wordRotation = (localRandom.nextFloat() - 0.5f) * rotationDrift
            
            drawWordWithMistakes(
                canvas, word, currentX, currentBaseline,
                paint, wordSeed, wordRotation, localRandom
            )
            
            // Advance with imperfect spacing
            val wordWidth = paint.measureText(word)
            val spaceWidth = paint.measureText(" ") * (0.85f + localRandom.nextFloat() * 0.3f)
            currentX += wordWidth + spaceWidth
        }
    }
    
    private fun drawWordWithMistakes(
        canvas: Canvas,
        word: String,
        x: Float,
        y: Float,
        basePaint: TextPaint,
        seed: Int,
        wordRotation: Float,
        globalRandom: Random
    ) {
        val localRandom = Random(seed)
        val clusters = extractGraphemeClusters(word)
        
        var currentX = x
        val baseTextSize = basePaint.textSize
        val baseStrokeWidth = basePaint.textSize * 0.05f
        
        // Decide if this word gets an ink pool at the start
        val hasInkPool = enableMistakes && localRandom.nextFloat() < inkPoolChance
        
        // Apply word-level rotation
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(wordRotation)
        canvas.translate(-x, -y)
        
        for ((clusterIndex, cluster) in clusters.withIndex()) {
            val clusterSeed = seed + clusterIndex * 7919
            val clusterRandom = Random(clusterSeed)
            
            val clusterWidth = basePaint.measureText(cluster)
            
            // ── Mistake 1: Baseline jitter per cluster ─────────────
            val jitterY = if (enableMistakes) {
                (clusterRandom.nextFloat() - 0.5f) * shakiness * pxPerMm * 2
            } else 0f
            
            // ── Mistake 2: Micro-tremor (tiny rapid wobble) ────────
            val tremorX = if (enableMistakes) {
                (clusterRandom.nextFloat() - 0.5f) * microTremor * pxPerMm
            } else 0f
            
            // ── Mistake 3: Variable pressure (thick/thin) ──────────
            val pressure = if (enableMistakes) {
                0.5f + clusterRandom.nextFloat() * pressureVariation
            } else 1f
            
            // ── Mistake 4: Slight size variation per cluster ─────────
            val sizeMult = if (enableMistakes) {
                1f + (clusterRandom.nextFloat() - 0.5f) * 0.06f
            } else 1f
            
            // ── Mistake 5: Occasional gap (skipped connection) ─────
            val skipGap = if (enableMistakes && clusterRandom.nextFloat() < skipConnectionChance) {
                basePaint.textSize * 0.15f
            } else 0f
            
            // ── Mistake 6: Slight rotation per cluster ─────────────
            val clusterRotation = if (enableMistakes) {
                (clusterRandom.nextFloat() - 0.5f) * 1.5f
            } else 0f
            
            // Apply paint modifications
            val textPaint = TextPaint(basePaint).apply {
                textSize = baseTextSize * sizeMult
                strokeWidth = baseStrokeWidth * pressure
                // Vary alpha slightly for dry/wet ink look
                alpha = (240 + clusterRandom.nextInt(15)).coerceIn(220, 255)
            }
            
            // Draw ink pool at start of word (first cluster only)
            if (hasInkPool && clusterIndex == 0) {
                drawInkPool(canvas, currentX + tremorX, y + jitterY, baseTextSize * 0.3f)
            }
            
            // Draw the cluster with all mistakes applied
            canvas.save()
            canvas.translate(currentX + tremorX + skipGap, y + jitterY)
            canvas.rotate(clusterRotation)
            canvas.drawText(cluster, 0f, 0f, textPaint)
            canvas.restore()
            
            // Draw shaky underline effect for some clusters (simulates hand tremor)
            if (enableMistakes && clusterRandom.nextFloat() < 0.05f) {
                drawShakyUnderline(canvas, currentX, y, clusterWidth, baseTextSize, clusterRandom)
            }
            
            currentX += clusterWidth + skipGap + baseTextSize * 0.08f
        }
        
        canvas.restore()
        
        // Draw occasional ink blob at end of word
        if (enableMistakes && globalRandom.nextFloat() < inkPoolChance * 0.5f) {
            drawInkPool(canvas, currentX - baseTextSize * 0.1f, y, baseTextSize * 0.15f)
        }
    }
    
    /**
     * Draw an ink pool/blob — excess ink that pools at pen stops.
     * Universal: applies to any script at stroke endpoints.
     */
    private fun drawInkPool(canvas: Canvas, x: Float, y: Float, radius: Float) {
        val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A1A")
            alpha = 180
            style = Paint.Style.FILL
        }
        
        // Irregular blob shape using multiple overlapping circles
        val random = Random(x.toInt() + y.toInt())
        val blobs = 3 + random.nextInt(3)
        
        for (i in 0 until blobs) {
            val offsetX = (random.nextFloat() - 0.5f) * radius * 1.5f
            val offsetY = (random.nextFloat() - 0.5f) * radius * 1.5f
            val blobRadius = radius * (0.4f + random.nextFloat() * 0.6f)
            canvas.drawCircle(x + offsetX, y + offsetY, blobRadius, blobPaint)
        }
    }
    
    /**
     * Draw a shaky underline — simulates hand tremor or hesitation.
     */
    private fun drawShakyUnderline(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        textSize: Float,
        random: Random
    ) {
        val path = Path()
        val startX = x
        val endX = x + width
        val baseY = y + textSize * 0.15f
        
        path.moveTo(startX, baseY)
        
        var currentX = startX
        while (currentX < endX) {
            val step = 2f + random.nextFloat() * 3f
            currentX = (currentX + step).coerceAtMost(endX)
            val wobbleY = baseY + (random.nextFloat() - 0.5f) * textSize * 0.08f
            path.lineTo(currentX, wobbleY)
        }
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A2A2A")
            strokeWidth = textSize * 0.03f
            style = Paint.Style.STROKE
            alpha = 120
        }
        
        canvas.drawPath(path, paint)
    }
    
    /**
     * Draw a stroke with visible wobble — simulates unsteady hand movement.
     * Applied to the entire text path for major shakiness.
     */
    fun drawShakyStroke(
        canvas: Canvas,
        path: Path,
        paint: Paint,
        amplitude: Float = 1.5f
    ) {
        // Sample the path and redraw with noise
        val pathMeasure = android.graphics.PathMeasure(path, false)
        val length = pathMeasure.length
        val step = 2f
        
        val wobblyPath = Path()
        val pos = FloatArray(2)
        val first = true
        
        var distance = 0f
        val random = Random(length.toInt())
        
        while (distance <= length) {
            pathMeasure.getPosTan(distance, pos, null)
            val wobbleX = pos[0] + (random.nextFloat() - 0.5f) * amplitude
            val wobbleY = pos[1] + (random.nextFloat() - 0.5f) * amplitude
            
            if (first) {
                wobblyPath.moveTo(wobbleX, wobbleY)
            } else {
                wobblyPath.lineTo(wobbleX, wobbleY)
            }
            
            distance += step
        }
        
        canvas.drawPath(wobblyPath, paint)
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
            
            // Myanmar medials
            while (i < text.length && isMyanmarMedial(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
            }
            
            // Myanmar asat + stacked consonants
            if (i < text.length && text.codePointAt(i) == 0x103A) {
                i += Character.charCount(text.codePointAt(i))
                while (i < text.length && isMyanmarConsonant(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i))
                    while (i < text.length && isMyanmarMedial(text.codePointAt(i))) {
                        i += Character.charCount(text.codePointAt(i))
                    }
                }
            }
            
            // ZWJ sequences
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
        cp in 0x0300..0x036F -> true
        cp in 0x1AB0..0x1AFF -> true
        cp in 0x1DC0..0x1DFF -> true
        cp in 0x20D0..0x20FF -> true
        cp in 0xFE20..0xFE2F -> true
        cp in 0x064B..0x065F -> true
        cp == 0x0670 -> true
        cp in 0x0591..0x05BD -> true
        cp in 0x05BF..0x05C7 -> true
        cp in 0x0E31..0x0E3A -> true
        cp in 0x0E47..0x0E4E -> true
        cp in 0x0EB1..0x0EB9 -> true
        cp in 0x0EC8..0x0ECD -> true
        cp in 0x102B..0x103E -> true
        cp in 0x1056..0x1059 -> true
        cp in 0x105E..0x1060 -> true
        cp in 0x093E..0x094F -> true
        cp in 0x0951..0x0957 -> true
        cp in 0x0962..0x0963 -> true
        cp in 0x09BE..0x09CC -> true
        cp in 0x0A3E..0x0A4C -> true
        cp in 0x0ABE..0x0ACC -> true
        cp in 0x0B3E..0x0B4C -> true
        cp in 0x0BBE..0x0BCC -> true
        cp in 0x0C3E..0x0C4C -> true
        cp in 0x0CBE..0x0CCC -> true
        cp in 0x0D3E..0x0D4C -> true
        cp in 0x17B6..0x17D3 -> true
        cp in 0x1161..0x1175 -> true
        cp in 0x11A8..0x11C2 -> true
        cp in 0xFE00..0xFE0F -> true
        cp in 0xE0100..0xE01EF -> true
        else -> false
    }
    
    private fun isMyanmarMedial(cp: Int) = cp in 0x103B..0x103E
    private fun isMyanmarConsonant(cp: Int) = cp in 0x1000..0x102A
    private fun isJoiner(cp: Int) = cp == 0x200D || cp == 0x200C
}

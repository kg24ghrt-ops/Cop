package com.pot.cil.hj.ui.view

import android.graphics.*
import android.text.TextPaint
import kotlin.math.*
import kotlin.random.Random

/**
 * Ultra-realistic human handwriting renderer.
 * 
 * Simulates physical ink on paper through multi-pass rendering.
 * Works with any script: Latin, CJK, Arabic, Thai, Devanagari, Myanmar, emoji.
 */
class HandwritingPaint {
    
    // ── Physical scale ─────────────────────────────────────────
    private val pxPerMm = 6.3f
    
    // ── Mistake / realism configuration ───────────────────────
    var enableMistakes: Boolean = true
    var shakiness: Float = 0.7f              // 0=perfect, 2=very shaky
    var inkPoolChance: Float = 0.12f         // Chance of ink blob at stroke start
    var pressureVariation: Float = 0.5f      // Thick/thin stroke variation
    var rotationDrift: Float = 1.5f          // Degrees of rotation drift per word
    var microTremor: Float = 0.35f           // Tiny wobble in strokes
    var skipConnectionChance: Float = 0.04f  // Gap in cursive-like connection
    var baselineWander: Float = 1.0f         // Baseline drifts up/down slightly
    var inkFeathering: Float = 0.6f          // How much ink bleeds (0=none, 2=heavy)
    var edgeRoughness: Float = 0.4f          // Paper texture interaction
    var penAngle: Float = 40f                // Degrees — ballpoint pen tilt
    var velocityPressure: Boolean = true       // Faster = thinner strokes
    
    // Correlated baseline drift (simulates hand/arm movement)
    private var baselineDriftAccumulator = 0f
    private var lastDriftSeed = 0
    
    /**
     * Draw text with human handwriting mistakes.
     * Multi-pass rendering for physical ink simulation.
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
        
        // Reset correlated drift for new line
        if (seed != lastDriftSeed) {
            baselineDriftAccumulator = 0f
            lastDriftSeed = seed
        }
        
        // Break into words for per-word drift
        val words = textString.split(" ")
        var currentX = x
        var currentBaseline = y
        
        for ((wordIndex, word) in words.withIndex()) {
            val wordSeed = seed + wordIndex * 9973
            val wordRandom = Random(wordSeed)
            
            // Correlated baseline wander (hand moves in smooth curves, not jumps)
            val targetDrift = (wordRandom.nextFloat() - 0.5f) * baselineWander * pxPerMm
            baselineDriftAccumulator += (targetDrift - baselineDriftAccumulator) * 0.3f
            currentBaseline += baselineDriftAccumulator * 0.4f
            
            // Per-word rotation drift
            val wordRotation = (wordRandom.nextFloat() - 0.5f) * rotationDrift
            
            drawWordWithRealism(
                canvas, word, currentX, currentBaseline,
                paint, wordSeed, wordRotation, localRandom
            )
            
            // Advance with imperfect spacing
            val wordWidth = paint.measureText(word)
            val spaceWidth = paint.measureText(" ") * (0.85f + localRandom.nextFloat() * 0.3f)
            currentX += wordWidth + spaceWidth
        }
    }
    
    private fun drawWordWithRealism(
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
        
        // Decide ink pool at word start
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
            
            // ── Physical mistakes per cluster ─────────────────────
            val jitterY = if (enableMistakes) {
                (clusterRandom.nextFloat() - 0.5f) * shakiness * pxPerMm * 2f
            } else 0f
            
            val tremorX = if (enableMistakes) {
                (clusterRandom.nextFloat() - 0.5f) * microTremor * pxPerMm
            } else 0f
            
            // Velocity-based pressure: wider clusters = faster = thinner
            val velocityFactor = if (velocityPressure && enableMistakes) {
                val normalizedWidth = (clusterWidth / baseTextSize).coerceIn(0.5f, 2f)
                1f - (normalizedWidth - 0.5f) * 0.12f
            } else 1f
            
            val pressure = if (enableMistakes) {
                (0.5f + clusterRandom.nextFloat() * pressureVariation) * velocityFactor
            } else 1f
            
            val sizeMult = if (enableMistakes) {
                1f + (clusterRandom.nextFloat() - 0.5f) * 0.05f
            } else 1f
            
            val skipGap = if (enableMistakes && clusterRandom.nextFloat() < skipConnectionChance) {
                baseTextSize * 0.1f
            } else 0f
            
            val clusterRotation = if (enableMistakes) {
                (clusterRandom.nextFloat() - 0.5f) * 1.2f
            } else 0f
            
            // ── Calculate draw position ─────────────────────────────
            // drawX is where we actually draw; currentX is the logical position
            val drawX = currentX + tremorX
            val drawY = y + jitterY
            
            // ── Multi-pass ink rendering ──────────────────────────
            // PASS 1: Ink bleed (feathering into paper)
            if (inkFeathering > 0f && enableMistakes) {
                drawInkBleedPass(canvas, cluster, drawX, drawY, basePaint, 
                    baseTextSize * sizeMult, pressure, clusterRandom)
            }
            
            // PASS 2: Main stroke with pen angle simulation
            drawMainStroke(canvas, cluster, drawX, drawY, basePaint,
                baseTextSize * sizeMult, pressure, penAngle, clusterRandom)
            
            // PASS 3: Edge noise (paper texture interaction)
            if (edgeRoughness > 0f && enableMistakes) {
                drawEdgeNoisePass(canvas, cluster, drawX, drawY, basePaint,
                    baseTextSize * sizeMult, pressure, clusterRandom)
            }
            
            // PASS 4: Dry/wet variation overlay
            if (enableMistakes) {
                drawDryWetOverlay(canvas, cluster, drawX, drawY, basePaint,
                    baseTextSize * sizeMult, pressure, clusterIndex, clusters.size, clusterRandom)
            }
            
            // Ink pool at word start (first cluster only)
            if (hasInkPool && clusterIndex == 0) {
                drawRealisticInkPool(canvas, drawX, drawY, baseTextSize * 0.22f, clusterRandom)
            }
            
            // Occasional shaky underline (hand tremor)
            if (enableMistakes && clusterRandom.nextFloat() < 0.03f) {
                drawShakyUnderline(canvas, drawX, y, clusterWidth, baseTextSize, clusterRandom)
            }
            
            // Advance to next cluster position — ONLY advance by measured width + skip gap
            currentX += clusterWidth + skipGap + baseTextSize * 0.02f
        }
        
        canvas.restore()
        
        // End-of-word ink blob
        if (enableMistakes && globalRandom.nextFloat() < inkPoolChance * 0.3f) {
            drawRealisticInkPool(canvas, currentX - baseTextSize * 0.05f, y, 
                baseTextSize * 0.1f, globalRandom)
        }
    }
    
    /**
     * PASS 1: Ink bleed — soft feathering that simulates ink soaking into paper fibers.
     */
    private fun drawInkBleedPass(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        basePaint: TextPaint,
        textSize: Float,
        pressure: Float,
        random: Random
    ) {
        val bleedPaint = TextPaint(basePaint).apply {
            this.textSize = textSize
            color = basePaint.color
            alpha = (35f * inkFeathering).toInt().coerceIn(10, 70)
            maskFilter = BlurMaskFilter(textSize * 0.035f * inkFeathering, BlurMaskFilter.Blur.NORMAL)
        }
        
        val passes = 2 + random.nextInt(2)
        for (i in 0 until passes) {
            val offsetX = (random.nextFloat() - 0.5f) * textSize * 0.025f * inkFeathering
            val offsetY = (random.nextFloat() - 0.5f) * textSize * 0.025f * inkFeathering
            
            canvas.save()
            canvas.translate(x + offsetX, y + offsetY)
            canvas.drawText(text, 0f, 0f, bleedPaint)
            canvas.restore()
        }
    }
    
    /**
     * PASS 2: Main stroke with pen angle simulation.
     */
    private fun drawMainStroke(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        basePaint: TextPaint,
        textSize: Float,
        pressure: Float,
        angle: Float,
        random: Random
    ) {
        val rad = Math.toRadians(angle.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val penOffset = textSize * 0.01f
        
        // Main paint
        val mainPaint = TextPaint(basePaint).apply {
            this.textSize = textSize
            strokeWidth = basePaint.textSize * 0.04f * pressure
            style = Paint.Style.FILL
            alpha = (240 + random.nextInt(15)).coerceIn(230, 255)
        }
        
        // Draw primary stroke
        canvas.save()
        canvas.translate(x, y)
        canvas.drawText(text, 0f, 0f, mainPaint)
        canvas.restore()
        
        // Secondary offset stroke for pen angle
        if (pressure > 0.6f) {
            val secondaryPaint = TextPaint(mainPaint).apply {
                alpha = (mainPaint.alpha * 0.3f).toInt()
                strokeWidth = mainPaint.strokeWidth * 0.4f
            }
            canvas.save()
            canvas.translate(x + penOffset * cosA, y + penOffset * sinA)
            canvas.drawText(text, 0f, 0f, secondaryPaint)
            canvas.restore()
        }
        
        // Micro-tremor
        if (microTremor > 0f && enableMistakes) {
            val tremorPaint = TextPaint(mainPaint).apply {
                alpha = (mainPaint.alpha * 0.2f).toInt()
            }
            val tremorX = (random.nextFloat() - 0.5f) * microTremor * pxPerMm * 0.4f
            val tremorY = (random.nextFloat() - 0.5f) * microTremor * pxPerMm * 0.4f
            canvas.Save()
            canvas.translate(x + tremorX, y + tremorY)
            canvas.drawText(text, 0f, 0f, tremorPaint)
            canvas.restore()
        }
    }
    
    /**
     * PASS 3: Edge noise — simulates paper tooth catching ink unevenly.
     */
    private fun drawEdgeNoisePass(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        basePaint: TextPaint,
        textSize: Float,
        pressure: Float,
        random: Random
    ) {
        val measurePaint = TextPaint(basePaint).apply { this.textSize = textSize }
        val textWidth = measurePaint.measureText(text)
        
        val noisePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = basePaint.color
            alpha = (50f * edgeRoughness * pressure).toInt().coerceIn(15, 100)
            strokeWidth = textSize * 0.012f
            style = Paint.Style.STROKE
        }
        
        val noiseCount = (textWidth / textSize * 6f * edgeRoughness).toInt().coerceAtLeast(0)
        
        for (i in 0 until noiseCount) {
            val nx = x + random.nextFloat() * textWidth
            val ny = y + (random.nextFloat() - 0.3f) * textSize * 0.5f
            val radius = textSize * 0.006f * (0.5f + random.nextFloat())
            
            if (random.nextFloat() < 0.3f) {
                val dotPaint = Paint(noisePaint).apply { style = Paint.Style.FILL }
                canvas.drawCircle(nx, ny, radius, dotPaint)
            } else {
                val len = textSize * 0.015f * random.nextFloat()
                val angle = random.nextFloat() * 2f * PI.toFloat()
                canvas.drawLine(
                    nx, ny,
                    nx + cos(angle) * len, ny + sin(angle) * len,
                    noisePaint
                )
            }
        }
    }
    
    /**
     * PASS 4: Dry/wet overlay — ink dries darker at the start of a stroke.
     */
    private fun drawDryWetOverlay(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        basePaint: TextPaint,
        textSize: Float,
        pressure: Float,
        clusterIndex: Int,
        totalClusters: Int,
        random: Random
    ) {
        // Wet ink at start
        if (clusterIndex == 0 && random.nextFloat() < 0.5f) {
            val wetPaint = TextPaint(basePaint).apply {
                this.textSize = textSize
                alpha = (25f * pressure).toInt()
                color = Color.BLACK
                maskFilter = BlurMaskFilter(textSize * 0.015f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.save()
            canvas.translate(x, y)
            canvas.drawText(text, 0f, 0f, wetPaint)
            canvas.restore()
        }
        
        // Dry spot
        if (random.nextFloat() < 0.06f) {
            val measurePaint = TextPaint(basePaint).apply { this.textSize = textSize }
            val textWidth = measurePaint.measureText(text)
            val dryWidth = textSize * (0.08f + random.nextFloat() * 0.2f)
            val dryX = x + random.nextFloat() * (textWidth - dryWidth).coerceAtLeast(1f)
            val dryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FEFCF3")
                alpha = 80 + random.nextInt(60)
                maskFilter = BlurMaskFilter(textSize * 0.025f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRect(dryX, y - textSize * 0.5f, dryX + dryWidth, y + textSize * 0.15f, dryPaint)
        }
    }
    
    /**
     * Realistic ink pool — irregular, organic blob.
     */
    private fun drawRealisticInkPool(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        random: Random
    ) {
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0D0D0D")
            alpha = 200
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(radius * 0.5f, BlurMaskFilter.Blur.NORMAL)
        }
        
        val blobCount = 3 + random.nextInt(3)
        for (i in 0 until blobCount) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val distance = random.nextFloat() * radius * 0.7f
            val bx = x + cos(angle) * distance
            val by = y + sin(angle) * distance * 0.7f
            val br = radius * (0.3f + random.nextFloat() * 0.7f)
            val alpha = (140 + random.nextInt(80)).coerceIn(100, 240)
            
            val blobPaint = Paint(basePaint).apply { this.alpha = alpha }
            canvas.drawCircle(bx, by, br, blobPaint)
        }
        
        val centerPaint = Paint(basePaint).apply {
            alpha = 240
            maskFilter = BlurMaskFilter(radius * 0.15f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(x, y, radius * 0.35f, centerPaint)
    }
    
    /**
     * Shaky underline — simulates hand tremor.
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
            val step = 2f + random.nextFloat() * 2f
            currentX = (currentX + step).coerceAtMost(endX)
            val wobbleY = baseY + (random.nextFloat() - 0.5f) * textSize * 0.08f
            path.lineTo(currentX, wobbleY)
        }
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#252525")
            strokeWidth = textSize * 0.02f
            style = Paint.Style.STROKE
            alpha = 90
            maskFilter = BlurMaskFilter(textSize * 0.008f, BlurMaskFilter.Blur.NORMAL)
        }
        
        canvas.drawPath(path, paint)
    }
    
    /**
     * Extract grapheme clusters — user-perceived characters.
     */
    private fun extractGraphemeClusters(text: String): List<String> {
        val clusters = mutableListOf<String>()
        var i = 0
        
        while (i < text.length) {
            val start = i
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)
            
            while (i < text.length && isCombining(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
            }
            
            while (i < text.length && isMyanmarMedial(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
            }
            
            if (i < text.length && text.codePointAt(i) == 0x103A) {
                i += Character.charCount(text.codePointAt(i))
                while (i < text.length && isMyanmarConsonant(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i))
                    while (i < text.length && isMyanmarMedial(text.codePointAt(i))) {
                        i += Character.charCount(text.codePointAt(i))
                    }
                }
            }
            
            while (i < text.length && isJoiner(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
                if (i < text.length && !isJoiner(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i))
                }
            }
            
            while (i < text.length && isVariationSelector(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
            }
            if (i < text.length && text.codePointAt(i) == 0xE007F) {
                i += Character.charCount(text.codePointAt(i))
            }
            
            if (i < text.length && isRegionalIndicator(codePoint) && 
                isRegionalIndicator(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
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
    private fun isVariationSelector(cp: Int) = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF
}

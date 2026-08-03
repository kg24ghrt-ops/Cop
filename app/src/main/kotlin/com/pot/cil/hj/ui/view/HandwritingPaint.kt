package com.pot.cil.hj.ui.view

import android.graphics.*
import android.text.TextPaint
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.*
import kotlin.math.*
import kotlin.random.Random

/**
 * HandwritingPaint – hybrid Kotlin / C++ handwriting renderer.
 *
 * - Text measurement & cluster extraction: Android (perfect Unicode support)
 * - Cluster‑level positioning & variation:   native C++ shaper
 * - Pixel‑level ink effects:                 native C++ engine
 *
 * All public APIs remain unchanged.
 */
class HandwritingPaint {

    // ── Physical scale ─────────────────────────────────────────
    private val pxPerMm = 6.3f

    // ── Mistake / realism configuration (optimised defaults) ──
    var enableMistakes: Boolean = true
    var shakiness: Float = 0.4f
    var inkPoolChance: Float = 0.05f
    var pressureVariation: Float = 0.3f
    var rotationDrift: Float = 0.8f
    var microTremor: Float = 0.2f
    var skipConnectionChance: Float = 0.02f
    var baselineWander: Float = 0.6f
    var inkFeathering: Float = 0.3f
    var edgeRoughness: Float = 0.2f
    var penAngle: Float = 30f
    var velocityPressure: Boolean = true

    var performanceMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                shakiness = 0.2f
                inkPoolChance = 0.02f
                pressureVariation = 0.15f
                rotationDrift = 0.4f
                microTremor = 0.1f
                skipConnectionChance = 0.01f
                baselineWander = 0.3f
                inkFeathering = 0.1f
                edgeRoughness = 0.1f
                penAngle = 25f
            }
        }

    // Correlated baseline drift
    private var baselineDriftAccumulator = 0f
    private var lastDriftSeed = 0

    // ── Native methods (dynamic registration via JNI_OnLoad) ───
    private external fun nativeApplyEffects(
        pixels: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        inkFeathering: Float,
        edgeRoughness: Float,
        enableMistakes: Boolean,
        performanceMode: Boolean
    )

    private external fun computeShaping(
        clusterStrings: Array<String>,
        clusterWidths: FloatArray,
        clusterCount: Int,
        baseTextSize: Float,
        shakiness: Float,
        microTremor: Float,
        pressureVariation: Float,
        sizeVariation: Float,
        skipChance: Float,
        skipWidth: Float,
        velocityPressure: Boolean,
        seed: Int
    ): FloatArray

    companion object {
        init {
            try {
                System.loadLibrary("handwriting_engine")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("HandwritingPaint", "Native library not found – effects disabled", e)
            }
        }
    }

    // ── Thread pool for parallel word rendering ────────────────
    private val renderExecutor: ExecutorService = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    )

    fun shutdown() {
        renderExecutor.shutdown()
    }

    /**
     * Public entry point – API unchanged.
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

        val textString = text.toString()
        val localRandom = Random(seed)

        if (seed != lastDriftSeed) {
            baselineDriftAccumulator = 0f
            lastDriftSeed = seed
        }

        val rawWords = textString.split(" ")
        data class WordParams(
            val word: String,
            val wordSeed: Int,
            val xPos: Float,
            val yPos: Float,
            val wordRotation: Float
        )
        val wordParamsList = mutableListOf<WordParams>()
        var currentX = x
        var currentBaseline = y
        var rawIndex = 0

        for (word in rawWords) {
            if (word.isEmpty()) {
                val spaceWidth = paint.measureText(" ") * (0.85f + localRandom.nextFloat() * 0.3f)
                currentX += spaceWidth
                rawIndex++
                continue
            }

            val wordSeed = seed + rawIndex * 9973
            val wordRandom = Random(wordSeed)

            val targetDrift = (wordRandom.nextFloat() - 0.5f) * baselineWander * pxPerMm
            baselineDriftAccumulator += (targetDrift - baselineDriftAccumulator) * 0.3f
            currentBaseline += baselineDriftAccumulator * 0.4f

            val wordRotation = (wordRandom.nextFloat() - 0.5f) * rotationDrift

            wordParamsList.add(WordParams(word, wordSeed, currentX, currentBaseline, wordRotation))

            val spaceWidth = paint.measureText(" ") * (0.85f + localRandom.nextFloat() * 0.3f)
            currentX += paint.measureText(word) + spaceWidth
            rawIndex++
        }

        val futures = mutableListOf<Future<Bitmap>>()
        for (params in wordParamsList) {
            futures.add(renderExecutor.submit(Callable {
                renderWordWithNativeEffects(
                    word = params.word,
                    basePaint = paint,
                    wordSeed = params.wordSeed,
                    wordRotation = params.wordRotation
                )
            }))
        }

        for (i in futures.indices) {
            val bitmap = futures[i].get()
            val p = wordParamsList[i]
            canvas.drawBitmap(bitmap, p.xPos, p.yPos, null)
            bitmap.recycle()
        }
    }

    private fun renderWordWithNativeEffects(
        word: String,
        basePaint: TextPaint,
        wordSeed: Int,
        wordRotation: Float
    ): Bitmap {
        val localPaint = TextPaint(basePaint).apply { isAntiAlias = true }
        val localMeasurePaint = TextPaint(localPaint)

        val baseTextSize = localPaint.textSize
        val textWidth = localMeasurePaint.measureText(word)
        val textHeight = baseTextSize * 1.6f
        val padding = baseTextSize * 0.15f

        val bitmapWidth = ceil(textWidth + padding * 2).toInt()
        val bitmapHeight = ceil(textHeight + padding * 2).toInt()
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val wordCanvas = Canvas(bitmap)
        wordCanvas.translate(padding, padding + textHeight * 0.75f)

        wordCanvas.save()
        wordCanvas.rotate(wordRotation)
        if (!performanceMode && penAngle != 0f && enableMistakes) {
            val shear = sin(Math.toRadians(penAngle.toDouble())).toFloat() * 0.08f
            wordCanvas.skew(-shear, 0f)
        }

        // Use the native shaper to draw clusters
        drawWordWithNativeShaping(
            canvas = wordCanvas,
            word = word,
            paint = localPaint,
            measurePaint = localMeasurePaint,
            wordSeed = wordSeed,
            baseTextSize = baseTextSize
        )

        wordCanvas.restore()

        // Apply pixel‑level ink effects
        val buffer = ByteBuffer.allocateDirect(bitmapWidth * bitmapHeight * 4)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()

        try {
            nativeApplyEffects(
                buffer, bitmapWidth, bitmapHeight, bitmapWidth,
                inkFeathering, edgeRoughness, enableMistakes, performanceMode
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.w("HandwritingPaint", "Native effect not available, using clean text")
        } catch (e: Exception) {
            Log.e("HandwritingPaint", "Native effect failed for word '$word'", e)
        }

        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    /**
     * Uses the native shaping engine to compute cluster positions,
     * then draws each cluster directly to the canvas.
     */
    private fun drawWordWithNativeShaping(
        canvas: Canvas,
        word: String,
        paint: TextPaint,
        measurePaint: TextPaint,
        wordSeed: Int,
        baseTextSize: Float
    ) {
        val clusters = extractGraphemeClusters(word).toTypedArray()
        if (clusters.isEmpty()) return

        val widths = FloatArray(clusters.size)
        for (i in clusters.indices) {
            widths[i] = measurePaint.measureText(clusters[i])
        }

        // Call native shaping engine
        val transforms = try {
            computeShaping(
                clusters, widths, clusters.size, baseTextSize,
                shakiness, microTremor, pressureVariation,
                0.025f,   // sizeVariation (from original logic)
                skipConnectionChance, baseTextSize * 0.1f,
                velocityPressure, wordSeed
            )
        } catch (e: UnsatisfiedLinkError) {
            // Fallback: use identity transforms
            FloatArray(clusters.size * 4) { i ->
                when (i % 4) {
                    0 -> 0f   // offsetX
                    1 -> 0f   // offsetY
                    2 -> 1f   // sizeScale
                    else -> 255f // alpha
                }
            }
        }

        var currentX = 0f
        for (i in clusters.indices) {
            val idx = i * 4
            val offsetX = transforms[idx]
            val offsetY = transforms[idx + 1]
            val scale = transforms[idx + 2]
            val alpha = transforms[idx + 3].toInt().coerceIn(0, 255)

            paint.textSize = baseTextSize * scale
            paint.alpha = alpha
            paint.style = Paint.Style.FILL

            canvas.save()
            canvas.translate(currentX + offsetX, offsetY)
            canvas.drawText(clusters[i], 0f, 0f, paint)
            canvas.restore()

            // Advance cursor – skip gap handled by native shaper?
            // (Native module currently doesn't output gap info, so we use local random)
            currentX += widths[i]
            val clusterRandom = Random(wordSeed + i * 7919)
            if (enableMistakes && clusterRandom.nextFloat() < skipConnectionChance) {
                currentX += baseTextSize * 0.1f
            }
            currentX += baseTextSize * 0.02f   // default spacing
        }
    }

    // ── Grapheme cluster extraction (unchanged) ────────────────
    private fun extractGraphemeClusters(text: String): List<String> {
        val clusters = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val start = i
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)

            while (i < text.length && isCombining(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
            while (i < text.length && isMyanmarMedial(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
            if (i < text.length && text.codePointAt(i) == 0x103A) {
                i += Character.charCount(text.codePointAt(i))
                while (i < text.length && isMyanmarConsonant(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i))
                    while (i < text.length && isMyanmarMedial(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
                }
            }
            while (i < text.length && isJoiner(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i))
                if (i < text.length && !isJoiner(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
            }
            while (i < text.length && isVariationSelector(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
            if (i < text.length && text.codePointAt(i) == 0xE007F) i += Character.charCount(text.codePointAt(i))
            if (i < text.length && isRegionalIndicator(codePoint) && isRegionalIndicator(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))

            clusters.add(text.substring(start, i))
        }
        return clusters
    }

    private fun isCombining(cp: Int): Boolean =
        Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
        Character.getType(cp) == Character.ENCLOSING_MARK.toInt() ||
        Character.getType(cp) == Character.COMBINING_SPACING_MARK.toInt()

    private fun isMyanmarMedial(cp: Int): Boolean =
        cp in setOf(0x103B, 0x103C, 0x103D, 0x103E, 0x105E, 0x105F, 0x1060, 0x1061, 0x1062, 0x1063, 0x1064)

    private fun isMyanmarConsonant(cp: Int): Boolean =
        cp in 0x1000..0x1021 || cp == 0x103F || cp in 0x104E..0x1055

    private fun isJoiner(cp: Int): Boolean = cp == 0x200D || cp == 0x200C

    private fun isVariationSelector(cp: Int): Boolean = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF
}
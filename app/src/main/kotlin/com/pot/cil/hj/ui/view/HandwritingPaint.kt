package com.pot.cil.hj.ui.view

import android.graphics.*
import android.text.TextPaint
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.*
import kotlin.math.*
import kotlin.random.Random

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

    // ── Native interface ───────────────────────────────────────
    // Dynamically registered – no name mangling, safe with R8
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

    companion object {
        init {
            try {
                System.loadLibrary("handwriting_engine")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("HandwritingPaint", "Native library not found – effects disabled", e)
            }
        }
    }

    // ── Thread pool ────────────────────────────────────────────
    private val renderExecutor: ExecutorService = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    )

    fun shutdown() {
        renderExecutor.shutdown()
    }

    // ── Simple 2D noise (pure function) ────────────────────────
    private fun noise2D(x: Float, y: Float, seed: Int): Float {
        val n = (x * 57f + y * 73f).toInt() xor seed
        val h = n * 0x9E3779B9.toInt()
        return (h shr 13).toFloat() / 32767f
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

        drawClustersOnCanvas(wordCanvas, word, localPaint, localMeasurePaint, wordSeed, baseTextSize)
        wordCanvas.restore()

        val buffer = ByteBuffer.allocateDirect(bitmapWidth * bitmapHeight * 4)
        bitmap.copyPixelsToBuffer(buffer)
        buffer.rewind()

        // ── Safe native call ─────────────────────────────────────
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

    private fun drawClustersOnCanvas(
        canvas: Canvas,
        word: String,
        paint: TextPaint,
        measurePaint: TextPaint,
        wordSeed: Int,
        baseTextSize: Float
    ) {
        val clusters = extractGraphemeClusters(word)
        var currentX = 0f

        for ((clusterIndex, cluster) in clusters.withIndex()) {
            val clusterSeed = wordSeed + clusterIndex * 7919
            val clusterRandom = Random(clusterSeed)

            val n1 = noise2D(currentX * 0.3f, 0f, wordSeed)
            val n2 = noise2D(currentX * 0.7f, 5f, wordSeed)

            val jitterY = if (enableMistakes) n1 * shakiness * pxPerMm * 2f else 0f
            val tremorX = if (enableMistakes) n2 * microTremor * pxPerMm * 0.7f else 0f

            measurePaint.textSize = baseTextSize
            val clusterWidth = measurePaint.measureText(cluster)

            val velocityFactor = if (velocityPressure && enableMistakes) {
                val normWidth = (clusterWidth / baseTextSize).coerceIn(0.5f, 2f)
                1f - (normWidth - 0.5f) * 0.12f
            } else 1f

            val progress = clusterIndex.toFloat() / clusters.size
            val pressureCurve = 1f - 0.2f * progress
            val pressure = if (enableMistakes) (0.5f + (n1 * 0.5f + 0.5f) * pressureVariation) * velocityFactor * pressureCurve else 1f
            val sizeMult = if (enableMistakes) 1f + n2 * 0.025f else 1f
            val skipGap = if (enableMistakes && clusterRandom.nextFloat() < skipConnectionChance) baseTextSize * 0.1f else 0f

            val drawX = currentX + tremorX
            val drawY = jitterY

            paint.textSize = baseTextSize * sizeMult
            paint.alpha = (240 + clusterRandom.nextInt(15)).coerceIn(230, 255)
            paint.style = Paint.Style.FILL

            canvas.save()
            canvas.translate(drawX, drawY)
            canvas.drawText(cluster, 0f, 0f, paint)
            canvas.restore()

            currentX += clusterWidth + skipGap + baseTextSize * 0.02f
        }
    }

    // ── Grapheme cluster extraction ────────────────────────────
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
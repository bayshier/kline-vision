package com.bayshier.klinevision.pattern

import com.bayshier.klinevision.render.ChartRenderer.Candle
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Parametric synthetic-pattern generator: every sample is labeled BY
 * CONSTRUCTION, which makes the eval set free and ground-truth-exact.
 * Noise (wicks, jitter) is added on top of the geometric skeleton so the
 * patterns look like real charts instead of textbook diagrams.
 */
object PatternGenerator {

    enum class Label { HEAD_AND_SHOULDERS, DOUBLE_TOP, DOUBLE_BOTTOM, ASCENDING_FLAG, UPTREND, DOWNTREND }

    data class Sample(
        val label: Label,
        val seed: Int,
        val candles: List<Candle>,
    )

    // ---- skeleton helpers ------------------------------------------------

    private fun buildCandles(
        anchorPoints: List<Double>,      // key prices the path passes through
        n: Int,
        noise: Double,
        rng: Random,
    ): List<Candle> {
        // Catmull-Rom-ish interpolation over anchors, then noise per bar
        val closes = interpolate(anchorPoints, n)
        return closes.mapIndexed { i, close ->
            val prev = closes[maxOf(0, i - 1)]
            val open = if (i == 0) close - noise * 0.4 else prev
            val drift = abs(close - open)
            val high = maxOf(open, close) + rng.nextDouble(0.0, noise + drift * 0.35)
            val low = minOf(open, close) - rng.nextDouble(0.0, noise + drift * 0.35)
            val vol = 800_000 + rng.nextDouble(0.0, 600_000.0) + drift * 2_000_000
            Candle(open, high, low, close, vol)
        }
    }

    private fun interpolate(anchors: List<Double>, n: Int): List<Double> {
        // smooth path through anchors using cosine easing between consecutive pairs
        val segs = anchors.size - 1
        val closes = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1) * segs
            val seg = minOf(segs - 1, t.toInt())
            val ft = t - seg
            val eased = (1 - kotlin.math.cos(ft * Math.PI)) / 2  // S-curve
            closes[i] = anchors[seg] * (1 - eased) + anchors[seg + 1] * eased
        }
        return closes.toList()
    }

    private fun wiggle(seed: Int, amplitude: Double, n: Int): List<Double> =
        List(n) { i -> sin(i * 0.7 + seed) * amplitude + sin(i * 0.23 + seed * 2) * amplitude * 0.6 }

    // ---- patterns ----------------------------------------------------------

    /**
     * Head and shoulders: left shoulder (lower peak), head (highest peak),
     * right shoulder (similar to left), joined by a neckline at the base.
     */
    fun headAndShoulders(seed: Int): Sample {
        val rng = Random(seed)
        val base = 20.0
        val a = listOf(
            base, base - 1.2,            // run-up
            base + 1.8,                  // left shoulder
            base - 0.6, base + 3.2,      // neckline dip, HEAD
            base - 0.6, base + 1.7,      // neckline, right shoulder
            base - 1.9,                  // breakdown
        )
        val n = 90
        val closes = interpolate(a, n)
        val wig = wiggle(seed, 0.16, n)
        val labeled = closes.mapIndexed { i, c -> c + wig[i] }
        return Sample(Label.HEAD_AND_SHOULDERS, seed, withNoise(labeled, rng))
    }

    /** Double top: two peaks at nearly the same height with a valley between. */
    fun doubleTop(seed: Int): Sample {
        val rng = Random(seed)
        val top = 30.0
        val a = listOf(
            top - 5.0, top - 2.0,
            top,                        // first peak
            top - 2.4,                  // valley
            top - 0.1,                  // second peak (same height)
            top - 3.2, top - 6.0,       // breakdown
        )
        val n = 84
        val closes = interpolate(a, n) + wiggle(seed, 0.14, n)
        return Sample(Label.DOUBLE_TOP, seed, withNoise(closes, rng))
    }

    /** Double bottom: mirror image of double top. */
    fun doubleBottom(seed: Int): Sample {
        val rng = Random(seed)
        val bottom = 10.0
        val a = listOf(
            bottom + 5.0, bottom + 2.0,
            bottom,                    // first trough
            bottom + 2.4,              // middle bump
            bottom + 0.1,              // second trough
            bottom + 3.2, bottom + 6.0,
        )
        val n = 84
        val closes = interpolate(a, n) + wiggle(seed, 0.14, n)
        return Sample(Label.DOUBLE_BOTTOM, seed, withNoise(closes, rng))
    }

    /**
     * Flag: strong pole (rally), then a small downward-sloping consolidation
     * channel, implying continuation upward.
     */
    fun ascendingFlag(seed: Int): Sample {
        val rng = Random(seed)
        val n = 88
        val pole = interpolate(listOf(12.0, 12.6, 18.4), (n * 0.55).toInt())
        val flagN = n - pole.size
        val flagStart = pole.last()
        val flag = interpolate(
            listOf(flagStart, flagStart - 0.5, flagStart - 0.15, flagStart - 0.7, flagStart - 0.3),
            flagN,
        )
        val closes = pole + flag
        return Sample(Label.ASCENDING_FLAG, seed, withNoise(closes, rng))
    }

    fun uptrend(seed: Int): Sample {
        val rng = Random(seed)
        val n = 90
        val closes = List(n) { 15.0 + it * 0.09 } + wiggle(seed, 0.15, n)
        return Sample(Label.UPTREND, seed, withNoise(closes, rng))
    }

    fun downtrend(seed: Int): Sample {
        val rng = Random(seed)
        val n = 90
        val closes = List(n) { 28.0 - it * 0.09 } + wiggle(seed, 0.15, n)
        return Sample(Label.DOWNTREND, seed, withNoise(closes, rng))
    }

    private fun withNoise(closes: List<Double>, rng: Random): List<Candle> {
        // derive OHLC bars from the noisy close path
        return closes.mapIndexed { i, close ->
            val prev = closes[maxOf(0, i - 1)]
            val open = if (i == 0) close else prev
            val move = abs(close - open)
            val wickUp = rng.nextDouble(0.02, 0.28) + move * 0.3
            val wickDn = rng.nextDouble(0.02, 0.28) + move * 0.3
            val vol = 900_000 + rng.nextDouble(0.0, 500_000.0) + move * 1_800_000
            Candle(open, close + wickUp, close - wickDn, close, vol)
        }
    }

    fun generateAll(seed: Int): List<Sample> = listOf(
        headAndShoulders(seed), doubleTop(seed), doubleBottom(seed),
        ascendingFlag(seed), uptrend(seed), downtrend(seed),
    )
}

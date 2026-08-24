package com.bayshier.klinevision.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders OHLC candles to a PNG with CN-market conventions (red = up,
 * green = down), MA5/MA10 overlays and a volume pane. Pure Java2D — no
 * dependencies, deterministic output for deterministic input.
 */
object ChartRenderer {

    data class Style(
        val width: Int = 900,
        val height: Int = 560,
        val bg: Color = Color(0x0B0E14),
        val grid: Color = Color(0x1C2333),
        val up: Color = Color(0xF6465D),
        val down: Color = Color(0x2EBD85),
        val ma5: Color = Color(0xF5C518),
        val ma10: Color = Color(0xB47EE5),
        val text: Color = Color(0x8B97A8),
        val axisFont: Font = Font(Font.MONOSPACED, Font.PLAIN, 13),
    )

    data class Candle(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    ) {
        val isUp get() = close >= open
    }

    fun render(
        candles: List<Candle>,
        title: String = "",
        out: File,
        style: Style = Style(),
    ): File {
        require(candles.size >= 2) { "need at least 2 candles" }
        val img = BufferedImage(style.width, style.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val padL = 16
        val padR = 64
        val padT = if (title.isBlank()) 24 else 52
        val padB = 22
        val volH = (style.height * 0.20).toInt()
        val chartW = style.width - padL - padR
        val priceH = style.height - padT - padB - volH - 12

        g.color = style.bg
        g.fillRect(0, 0, style.width, style.height)

        if (title.isNotBlank()) {
            g.color = Color(0xE6EDF3)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
            g.drawString(title, padL, 34)
        }

        // ---- price range & MA ----
        val ma5 = sma(candles.map { it.close }, 5)
        val ma10 = sma(candles.map { it.close }, 10)
        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        candles.forEach { hi = maxOf(hi, it.high); lo = minOf(lo, it.low) }
        listOf(ma5, ma10).forEach { ma ->
            ma.forEach { v -> if (!v.isNaN()) { hi = maxOf(hi, v); lo = minOf(lo, v) } }
        }
        val pad = (hi - lo).coerceAtLeast(1e-6) * 0.06
        hi += pad; lo -= pad

        fun y(price: Double): Int {
            val t = (hi - price) / (hi - lo)
            return padT + (t * priceH).toInt()
        }
        val slot = chartW.toDouble() / candles.size
        val bodyW = maxOf(2.0, slot * 0.66)

        // ---- grid + axis ----
        g.font = style.axisFont
        for (i in 0..4) {
            val gy = padT + priceH * i / 4
            g.color = style.grid
            g.drawLine(padL, gy, padL + chartW, gy)
            val price = hi - (hi - lo) * i / 4
            g.color = style.text
            g.drawString("%.2f".format(price), padL + chartW + 8, gy + 5)
        }

        // ---- volume pane ----
        val volTop = padT + priceH + 12
        val maxVol = candles.maxOf { it.volume }

        // ---- candles ----
        candles.forEachIndexed { i, c ->
            val cx = (padL + slot * i + slot / 2).toInt()
            val color = if (c.isUp) style.up else style.down
            g.color = color
            // wick
            g.stroke = BasicStroke(1.5f)
            g.drawLine(cx, y(c.high), cx, y(c.low))
            // body
            val yTop = y(maxOf(c.open, c.close))
            val yBot = y(minOf(c.open, c.close))
            g.fillRect((cx - bodyW / 2).toInt(), yTop, bodyW.toInt(), maxOf(2, yBot - yTop))
            // volume bar
            val vh = ((c.volume / maxVol) * (volH - 6)).toInt().coerceAtLeast(2)
            g.fillRect((cx - bodyW / 2).toInt(), volTop + volH - vh, bodyW.toInt(), vh)
        }

        // ---- MA lines ----
        fun drawMa(ma: List<Double>, color: Color) {
            g.color = color
            g.stroke = BasicStroke(1.8f)
            var started = false
            ma.forEachIndexed { i, v ->
                if (v.isNaN()) return@forEachIndexed
                val cx = (padL + slot * i + slot / 2).toInt()
                if (!started) {
                    // move needs polymorphism-free path: use drawLine chain
                    started = true
                }
                if (i > 0 && !ma[i - 1].isNaN()) {
                    val px = (padL + slot * (i - 1) + slot / 2).toInt()
                    g.drawLine(px, y(ma[i - 1]), cx, y(v))
                }
            }
        }
        drawMa(ma5, style.ma5)
        drawMa(ma10, style.ma10)

        // ---- legend ----
        g.font = style.axisFont
        g.color = style.ma5
        g.drawString("MA5", padL + 4, padT - 8)
        g.color = style.ma10
        g.drawString("MA10", padL + 44, padT - 8)

        g.dispose()
        out.parentFile?.mkdirs()
        ImageIO.write(img, "png", out)
        return out
    }

    private fun sma(values: List<Double>, period: Int): List<Double> {
        val out = DoubleArray(values.size) { Double.NaN }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out.toList()
    }
}

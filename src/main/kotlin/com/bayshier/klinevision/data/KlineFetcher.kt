package com.bayshier.klinevision.data

import com.bayshier.klinevision.render.ChartRenderer.Candle
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal A-share kline fetcher (Eastmoney primary, Tencent ifzq fallback —
 * same dual-source approach proven in kline-mcp: Eastmoney's CDN rejects
 * some JVM TLS fingerprints, so the fallback is load-bearing).
 */
class KlineFetcher {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchDaily(code: String, limit: Int = 90): List<Candle> =
        try {
            eastmoney(secid(code), limit)
        } catch (e: Exception) {
            tencent(code, limit)
        }

    private fun secid(code: String): String = when (code.first()) {
        '6' -> "1.$code"
        else -> "0.$code"
    }

    private fun tencentCode(code: String): String = when (code.first()) {
        '6' -> "sh$code"
        else -> "sz$code"
    }

    private fun eastmoney(secid: String, limit: Int): List<Candle> {
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
            "?secid=$secid&fields1=f1&fields2=f51,f52,f53,f54,f55,f56" +
            "&klt=101&fqt=1&end=20500101&lmt=$limit"
        val body = get(url)
        val klines = json.parseToJsonElement(body)
            .jsonObject["data"]!!.jsonObject["klines"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        return klines.map { line ->
            val f = line.split(",")
            Candle(f[1].toDouble(), f[3].toDouble(), f[4].toDouble(), f[2].toDouble(), f[5].toDouble())
        }
    }

    private fun tencent(code: String, limit: Int): List<Candle> {
        val tc = tencentCode(code)
        val body = get("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$tc,day,,,$limit,qfq")
        val data = json.parseToJsonElement(body)
            .jsonObject["data"]!!.jsonObject[tc]!!.jsonObject
        val rows = (data["qfqday"] ?: data["day"])!!
            .jsonArray.map { row ->
                // Dividend days carry a 7th element — a JSON object with
                // 分红 info ("10派3.6元"). Only the first 6 columns are OHLCV.
                row.jsonArray.take(6).map { c -> c.jsonPrimitive.content }
            }
        return rows.takeLast(limit).map { f ->
            Candle(f[1].toDouble(), f[3].toDouble(), f[4].toDouble(), f[2].toDouble(), f[5].toDoubleOrNull() ?: 0.0)
        }
    }

    private fun get(url: String): String =
        http.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "kline-vision/0.1")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
}

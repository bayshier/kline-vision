package com.bayshier.klinevision.vlm

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Minimal OpenAI-compatible vision client (works with OpenAI, GLM, Qwen,
 * Moonshot, OpenRouter, local vLLM/LM Studio — anything speaking
 * /chat/completions with image_url). Credentials come from the environment:
 *
 *   KLINE_VISION_API_KEY   (required for analyze)
 *   KLINE_VISION_BASE_URL  (default: https://api.openai.com/v1)
 *   KLINE_VISION_MODEL     (default: gpt-4o-mini)
 */
class VlmClient(
    private val apiKey: String? = System.getenv("KLINE_VISION_API_KEY"),
    private val baseUrl: String = System.getenv("KLINE_VISION_BASE_URL") ?: "https://api.openai.com/v1",
    private val model: String = System.getenv("KLINE_VISION_MODEL") ?: "gpt-4o-mini",
) {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    data class Prediction(
        val pattern: String,
        val confidence: Double,
        val evidence: String,
        val raw: String,
    )

    fun hasCredentials(): Boolean = !apiKey.isNullOrBlank()

    /**
     * Ask the VLM to classify a rendered chart image. The prompt enforces
     * strict JSON output so callers can parse deterministically.
     */
    fun classifyChart(image: File, extraContext: String = ""): Prediction {
        val key = apiKey ?: error(
            "缺少 KLINE_VISION_API_KEY 环境变量。支持任意 OpenAI 兼容服务：" +
                "设置 KLINE_VISION_BASE_URL 与 KLINE_VISION_MODEL。",
        )
        val b64 = Base64.getEncoder().encodeToString(image.readBytes())
        val body = """
            {
              "model": "$model",
              "messages": [
                {
                  "role": "system",
                  "content": "你是专业的K线技术分析师。分析图表并仅输出 JSON：{\"pattern\": \"形态名\", \"confidence\": 0.0-1.0, \"evidence\": \"判定依据\"}。形态包括: HEAD_AND_SHOULDERS, DOUBLE_TOP, DOUBLE_BOTTOM, ASCENDING_FLAG, UPTREND, DOWNTREND, NONE。"
                },
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "识别这张K线图的技术形态。$extraContext"},
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64,$b64"}}
                  ]
                }
              ],
              "max_tokens": 300
            }
        """.trimIndent()

        val resp = http.send(
            HttpRequest.newBuilder(URI.create("$baseUrl/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (resp.statusCode() != 200) error("VLM HTTP ${resp.statusCode()}: ${resp.body().take(300)}")
        return parse(resp.body())
    }

    /** Extracts the content string then the first JSON object inside it. */
    internal fun parse(completionJson: String): Prediction {
        val content = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(completionJson)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\n", "\n")
            ?: error("response has no content: ${completionJson.take(200)}")
        val obj = Regex("\\{[^{}]*\\}").find(content)?.value
            ?: return Prediction("NONE", 0.0, content.take(200), content)
        val pattern = Regex("\"pattern\"\\s*:\\s*\"([^\"]+)\"").find(obj)?.groupValues?.get(1) ?: "NONE"
        val confidence = Regex("\"confidence\"\\s*:\\s*([0-9.]+)").find(obj)
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val evidence = Regex("\"evidence\"\\s*:\\s*\"([^\"]*)\"").find(obj)?.groupValues?.get(1) ?: ""
        return Prediction(pattern, confidence, evidence, content)
    }
}

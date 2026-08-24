package com.bayshier.klinevision.server

import com.bayshier.klinevision.data.KlineFetcher
import com.bayshier.klinevision.pattern.PatternGenerator
import com.bayshier.klinevision.render.ChartRenderer
import com.bayshier.klinevision.vlm.VlmClient
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import java.io.File

/**
 * MCP tools for kline-vision:
 *   render_synthetic     — labeled demo chart, no VLM key needed
 *   generate_eval        — render the full benchmark set
 *   classify_chart       — image file -> VLM pattern judgment
 *   analyze_stock        — FULL LOOP: code -> live klines -> render -> VLM
 */
object VisionTools {

    private val fetcher = KlineFetcher()
    private val vlm = VlmClient()
    private val workDir = File(System.getProperty("user.dir"), "out").apply { mkdirs() }

    private fun schema(properties: Map<String, Any>, required: List<String> = emptyList()): Map<String, Any> =
        buildMap {
            put("type", "object"); put("properties", properties)
            if (required.isNotEmpty()) put("required", required)
        }

    private fun str(desc: String) = buildMap {
        put("type", "string"); put("description", desc)
    }

    private fun num(desc: String) = buildMap {
        put("type", "integer"); put("description", desc)
    }

    private fun text(msg: String): CallToolResult =
        CallToolResult(listOf(TextContent(msg)), false, null, null)

    private fun err(msg: String): CallToolResult =
        CallToolResult(listOf(TextContent("""{"error": "$msg"}""")), true, null, null)

    fun all(): List<SyncToolSpecification> = listOf(
        toolRenderSynthetic(), toolGenerateEval(), toolClassify(), toolAnalyzeStock(),
    )

    // ---- render_synthetic ----------------------------------------------

    private fun toolRenderSynthetic() = SyncToolSpecification(
        Tool(
            "render_synthetic",
            "渲染一张带标签的合成K线形态图（头肩顶/双顶/双底/旗形等），无需 VLM key。返回图片路径",
            null,
            schema(
                mapOf(
                    "pattern" to str("形态：head_and_shoulders / double_top / double_bottom / ascending_flag / uptrend / downtrend"),
                    "seed" to num("随机种子（默认 1）"),
                ),
            ),
            null, null, null,
        ),
    ) { _, req -> runCatching {
        val pattern = req.arguments()["pattern"]?.toString() ?: "head_and_shoulders"
        val seed = (req.arguments()["seed"] as? Int) ?: 1
        val sample = when (pattern.lowercase()) {
            "head_and_shoulders" -> PatternGenerator.headAndShoulders(seed)
            "double_top" -> PatternGenerator.doubleTop(seed)
            "double_bottom" -> PatternGenerator.doubleBottom(seed)
            "ascending_flag" -> PatternGenerator.ascendingFlag(seed)
            "uptrend" -> PatternGenerator.uptrend(seed)
            "downtrend" -> PatternGenerator.downtrend(seed)
            else -> error("未知形态: $pattern")
        }
        val out = File(workDir, "synthetic_${pattern}_$seed.png")
        ChartRenderer.render(sample.candles, "SYNTHETIC ${sample.label}", out)
        text("""{"path": "${out.absolutePath}", "label": "${sample.label}", "candles": ${sample.candles.size}}""")
    }.getOrElse { err(it.message ?: "render failed") } }

    // ---- generate_eval ----------------------------------------------------

    private fun toolGenerateEval() = SyncToolSpecification(
        Tool(
            "generate_eval",
            "渲染完整评测集（6 种形态 × N 变体）到 out/eval 目录，返回 manifest 路径",
            null,
            schema(mapOf("variants" to num("每种形态的变体数（默认 3）"))),
            null, null, null,
        ),
    ) { _, req -> runCatching {
        val variants = ((req.arguments()["variants"] as? Int) ?: 3).coerceIn(1, 20)
        val dir = File(workDir, "eval").apply { mkdirs() }
        val entries = StringBuilder("[")
        var count = 0
        PatternGenerator.Label.entries.forEach { label ->
            repeat(variants) { v ->
                val sample = PatternGenerator.generateAll(1000 + v * 7 + label.ordinal * 97)
                    .first { it.label == label }
                val name = "${label.name.lowercase()}_${v}.png"
                ChartRenderer.render(sample.candles, label.name, File(dir, name))
                entries.append("{\"file\": \"$name\", \"label\": \"${label.name}\"},")
                count++
            }
        }
        entries.append("]")
        val manifest = File(dir, "manifest.json")
        manifest.writeText("""{"samples": ${entries.toString().replace(",]", "]")}}""")
        text("""{"dir": "${dir.absolutePath}", "count": $count, "manifest": "${manifest.absolutePath}"}""")
    }.getOrElse { err(it.message ?: "eval failed") } }

    // ---- classify_chart -----------------------------------------------------

    private fun toolClassify() = SyncToolSpecification(
        Tool(
            "classify_chart",
            "用视觉大模型识别一张K线图的技术形态（需要 KLINE_VISION_API_KEY）",
            null,
            schema(mapOf("image_path" to str("本地图片路径")), listOf("image_path")),
            null, null, null,
        ),
    ) { _, req -> runCatching {
        val path = req.arguments()["image_path"]?.toString() ?: error("missing image_path")
        val file = File(path)
        if (!file.canRead()) error("图片不存在: $path")
        if (!vlm.hasCredentials()) {
            return@runCatching err("缺少 KLINE_VISION_API_KEY。支持任意 OpenAI 兼容服务（设 KLINE_VISION_BASE_URL/KLINE_VISION_MODEL）。可先用 render_synthetic 渲染演示图。")
        }
        val p = vlm.classifyChart(file)
        text("""{"pattern": "${p.pattern}", "confidence": ${p.confidence}, "evidence": "${p.evidence}"}""")
    }.getOrElse { err(it.message ?: "classify failed") } }

    // ---- analyze_stock：完整闭环 -------------------------------------------

    private fun toolAnalyzeStock() = SyncToolSpecification(
        Tool(
            "analyze_stock",
            "完整闭环：股票代码 → 拉取真实日K（东财主源/腾讯容灾）→ 渲染A股风K线图 → VLM 识别技术形态",
            null,
            schema(
                mapOf(
                    "code" to str("6 位股票代码，如 000001"),
                    "limit" to num("K线数量（默认 90）"),
                ),
                listOf("code"),
            ),
            null, null, null,
        ),
    ) { _, req -> runCatching {
        val code = req.arguments()["code"]?.toString() ?: error("missing code")
        if (!Regex("\\d{6}").matches(code)) error("股票代码应为 6 位数字")
        val limit = ((req.arguments()["limit"] as? Int) ?: 90).coerceIn(30, 250)

        val candles = fetcher.fetchDaily(code, limit)
        if (candles.size < 30) error("K线数据不足: ${candles.size}")

        val out = File(workDir, "stock_$code.png")
        ChartRenderer.render(candles, "$code · daily", out)

        if (!vlm.hasCredentials()) {
            return@runCatching text(
                """{"code": "$code", "candles": ${candles.size}, """ +
                    """"chart": "${out.absolutePath}", """ +
                    """"note": "已渲染真实行情图。设置 KLINE_VISION_API_KEY 后可自动调用 VLM 识别形态。"}""",
            )
        }
        val p = vlm.classifyChart(out, "这是 $code 最近 $limit 个交易日的日K线。")
        text("""{"code": "$code", "candles": ${candles.size}, "chart": "${out.absolutePath}", """ +
            """"pattern": "${p.pattern}", "confidence": ${p.confidence}, "evidence": "${p.evidence}"}""")
    }.getOrElse { err(it.message ?: "analyze failed") } }
}

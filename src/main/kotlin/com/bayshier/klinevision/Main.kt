package com.bayshier.klinevision

import com.bayshier.klinevision.pattern.PatternGenerator
import com.bayshier.klinevision.pattern.PatternGenerator.Label
import com.bayshier.klinevision.render.ChartRenderer
import java.io.File

/**
 * kline-vision CLI / MCP server.
 *
 *   eval    — generate the labeled synthetic eval set as PNG + manifest
 *   serve   — run as a stdio MCP server (4 tools)
 */
fun main(args: Array<String>) {
    when (args.firstOrNull() ?: "eval") {
        "serve" -> serveMcp()
        "eval" -> generateEvalSet(
            outDir = File(args.getOrNull(1) ?: "eval"),
            variants = (args.getOrNull(2)?.toIntOrNull() ?: 3),
        )
        else -> {
            println("用法: kline-vision eval [输出目录] [变体数] | kline-vision serve")
        }
    }
}

private fun serveMcp() {
    val mapper = io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(
        tools.jackson.databind.json.JsonMapper.builder().build(),
    )
    io.modelcontextprotocol.server.McpServer.sync(
        io.modelcontextprotocol.server.transport.StdioServerTransportProvider(mapper),
    )
        .serverInfo("kline-vision", "0.1.0")
        .instructions(
            "K线形态视觉识别 MCP：渲染K线图并调用视觉大模型识别技术形态。" +
                "render_synthetic 免 key 演示；analyze_stock 为完整闭环（真实行情→渲染→识别，" +
                "需 KLINE_VISION_API_KEY）；generate_eval 生成评测集。",
        )
        .tools(*com.bayshier.klinevision.server.VisionTools.all().toTypedArray())
        .build()
}

private fun generateEvalSet(outDir: File, variants: Int) {
    outDir.mkdirs()
    val manifest = StringBuilder()
    manifest.AppendLine("{")
    manifest.AppendLine("  \"description\": \"kline-vision 合成形态评测集：标签由构造保证（ground truth exact）\",")
    manifest.AppendLine("  \"samples\": [")

    val all = mutableListOf<Pair<String, String>>() // file -> label
    Label.entries.forEach { label ->
        val samples = (1..variants).map { v ->
            // 同一形态不同种子 → 不同噪声与摆动
            when (label) {
                Label.HEAD_AND_SHOULDERS -> PatternGenerator.headAndShoulders(1000 + v * 7 + label.ordinal)
                Label.DOUBLE_TOP -> PatternGenerator.doubleTop(2000 + v * 7 + label.ordinal)
                Label.DOUBLE_BOTTOM -> PatternGenerator.doubleBottom(3000 + v * 7 + label.ordinal)
                Label.ASCENDING_FLAG -> PatternGenerator.ascendingFlag(4000 + v * 7 + label.ordinal)
                Label.UPTREND -> PatternGenerator.uptrend(5000 + v * 7 + label.ordinal)
                Label.DOWNTREND -> PatternGenerator.downtrend(6000 + v * 7 + label.ordinal)
            }
        }
        samples.forEachIndexed { i, s ->
            val name = "${label.name.lowercase()}_$i.png"
            ChartRenderer.render(
                candles = s.candles,
                title = label.name,
                out = File(outDir, name),
            )
            all += name to label.name
            manifest.AppendLine("    {\"file\": \"$name\", \"label\": \"${label.name}\"},")
        }
    }
    manifest.AppendLine("  ]")
    manifest.AppendLine("}")
    // remove trailing comma of last entry
    val json = manifest.toString().replace(",\n  ]", "\n  ]")
    File(outDir, "manifest.json").writeText(json)
    println("生成 ${all.size} 个样本 → ${outDir.absolutePath}")
    all.groupBy { it.second }.forEach { (label, files) ->
        println("  $label: ${files.size} 张")
    }
}

private fun StringBuilder.AppendLine(s: String) = appendLine(s)

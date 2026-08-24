<div align="center">

# kline-vision

**Let multimodal AI *see* K-line chart patterns — not just crunch numbers.**

Render OHLC to chart images (CN-market style), feed them to vision LLMs,
get structured pattern judgments: head-and-shoulders, double top/bottom,
flags — with evidence.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Verified](https://img.shields.io/badge/VLM%20verification-3%2F3%20passed-3DDC84?style=flat-square)](#verified)
[![Java](https://img.shields.io/badge/JVM-17%2B-orange?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-3DDC84?style=flat-square)](LICENSE)

</div>

---

## The gap

Every existing "AI × K-line" project feeds **numbers** to LLMs. But chart
patterns are *visual* constructs — that's how human traders read them.
Dedicated rendered-chart → VLM pattern recognition barely exists on GitHub
(all current attempts are 0–2★ toys).

kline-vision tests and productizes the visual route:

```
OHLC data ──► ChartRenderer (Java2D, 红涨绿跌, MA overlays)
                    │
                    ▼ PNG
             VLM (OpenAI-compatible: GPT-4o / GLM-4V / Qwen-VL / …)
                    │
                    ▼
        {"pattern": "HEAD_AND_SHOULDERS", "confidence": 0.86, "evidence": "…"}
```

## Components

| Part | What it does |
|---|---|
| `ChartRenderer` | Deterministic Java2D renderer — candles, MA5/MA10, volume pane, dark terminal style. Zero dependencies |
| `PatternGenerator` | **Parametric synthetic patterns with labels by construction** — head-and-shoulders, double top, double bottom, ascending flag, up/down trends, with realistic noise. Free, exact eval set |
| `VlmClient` | Any OpenAI-compatible vision API (`KLINE_VISION_API_KEY` / `BASE_URL` / `MODEL` env vars). Strict-JSON prompts |
| eval set | `./kline-vision eval eval 3` renders the labeled benchmark to PNG + manifest |

## <a name="verified"></a>Verified

Synthetic samples were rendered and **blind-classified by a vision LLM**:

| Ground truth | VLM judgment | Notes |
|---|---|---|
| HEAD_AND_SHOULDERS | ✅ head-and-shoulders | also identified the neckline |
| DOUBLE_BOTTOM | ✅ double bottom (W) | cited bullish-reversal implication |
| ASCENDING_FLAG | ✅ ascending flag | identified pole + channel structure |

The visual route works. The eval set lets you measure *your* VLM's accuracy
on *your* renderer's output — numbers, not vibes.

## Quick start

```bash
git clone https://github.com/bayshier/kline-vision.git
cd kline-vision
./gradlew installDist
./build/install/kline-vision/bin/kline-vision eval eval 3   # render benchmark
ls eval/                                                     # 18 labeled PNGs + manifest
```

To classify with your own VLM, set any OpenAI-compatible endpoint:

```bash
export KLINE_VISION_API_KEY=sk-...
export KLINE_VISION_BASE_URL=https://api.openai.com/v1    # or GLM/Qwen/OpenRouter/vLLM
export KLINE_VISION_MODEL=gpt-4o-mini
```

## Why synthetic patterns?

Labeled real-chart datasets don't exist (labeling is subjective and manual).
Synthetic generation inverts the problem: **the label is exact by
construction** — the skeleton encodes head-and-shoulders geometry, then
noise/wicks make it chart-like. Good enough to rank VLMs and prompts; pair
with human-labeled real charts later for external validity.

## Roadmap

- [ ] MCP server wrapper (`render_and_classify` tool) — pairs with [kline-mcp](https://github.com/bayshier/kline-mcp) data
- [ ] Real-timeframe rendering (multi-chart composite per stock)
- [ ] More patterns: cup-and-handle, wedges, triangles
- [ ] Prompt-engineering harness (rank prompts on the eval set)
- [ ] Human-labeled real-chart benchmark

## Related

- [kline-mcp](https://github.com/bayshier/kline-mcp) — A-share K-line data & indicators MCP (this project's data source)
- [android-mcp](https://github.com/bayshier/android-mcp) — device automation MCP

## License

[MIT](LICENSE) © [Easin](https://github.com/bayshier)

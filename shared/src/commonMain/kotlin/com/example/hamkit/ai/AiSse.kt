package com.example.hamkit.ai

/**
 * 一个 SSE 帧：`event:` 名字（可选）+ 合并后的 `data:` 内容。
 *
 * 三家协议的流都用 `text/event-stream`，但细节各不相同：
 * - OpenAI：只有 `data:`，结束标记是 `data: [DONE]`；
 * - Anthropic：`event:` 与 `data:` 成对出现（message_start / content_block_delta …）；
 * - Gemini：`data:` 里是完整 JSON，一个帧可能含多个候选块。
 *
 * 解析器只负责「按空行分帧 + 合并多行 data」，协议语义在 [AiStreamDecoder] 里。
 */
data class AiSseFrame(
    val event: String?,
    val data: String,
)

/**
 * 增量 SSE 解析器：喂进任意切分的文本片段，吐出完整帧。
 *
 * 网络层（iOS `URLSession.AsyncBytes` / Android OkHttp source）给出的分片边界与行
 * 边界无关，可能把一个 JSON 切成两半，所以必须缓存**不完整的最后一行**。
 */
class AiSseParser {

    private val lineBuffer = StringBuilder()
    private val dataBuffer = StringBuilder()
    private var eventName: String? = null

    /** 喂入一段文本，返回本次能凑齐的完整帧。 */
    fun feed(chunk: String): List<AiSseFrame> {
        if (chunk.isEmpty()) return emptyList()
        lineBuffer.append(chunk)
        val frames = ArrayList<AiSseFrame>()
        while (true) {
            val newline = lineBuffer.indexOf('\n')
            if (newline < 0) break
            var line = lineBuffer.substring(0, newline)
            lineBuffer.delete(0, newline + 1)
            // 兼容 CRLF
            if (line.endsWith("\r")) line = line.dropLast(1)
            handleLine(line, frames)
        }
        return frames
    }

    /** 流结束时把残余内容也吐出来（有些实现最后一行没有换行）。 */
    fun flush(): List<AiSseFrame> {
        val frames = ArrayList<AiSseFrame>()
        if (lineBuffer.isNotEmpty()) {
            val line = lineBuffer.toString().removeSuffix("\r")
            lineBuffer.setLength(0)
            handleLine(line, frames)
        }
        dispatch(frames)
        return frames
    }

    private fun handleLine(line: String, frames: MutableList<AiSseFrame>) {
        when {
            line.isEmpty() -> dispatch(frames)

            line.startsWith(":") -> Unit // 注释行（含厂商的心跳）

            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()

            line.startsWith("data:") -> {
                val payload = line.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.drop(1) else it
                }
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(payload)
            }

            // 非 SSE 的整段 JSON（部分网关忽略 stream=true，直接回一条 JSON）：
            // 当成一个独立帧立刻派发，不要等空行。
            line.trimStart().startsWith("{") -> {
                frames.add(AiSseFrame(event = null, data = line.trim()))
                eventName = null
            }

            else -> Unit // id: / retry: 等字段对本项目无意义
        }
    }

    private fun dispatch(frames: MutableList<AiSseFrame>) {
        val data = dataBuffer.toString().trim()
        dataBuffer.setLength(0)
        val name = eventName
        eventName = null
        if (data.isEmpty()) return
        frames.add(AiSseFrame(event = name, data = data))
    }
}

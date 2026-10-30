package com.example.hamkit.data.ai

import okio.BufferedSource

/** 一行文本的来源。抽成接口是为了单测能直接喂字符串，不依赖网络栈。 */
fun interface LineSource {
    fun readLine(): String?
}

fun BufferedSource.asLineSource(): LineSource = LineSource { readUtf8Line() }

/** 一个 SSE 帧：`event:`（可能为空）+ 合并后的 `data:` 内容。 */
data class SseFrame(val event: String?, val data: String)

/**
 * Server-Sent Events 解析器（三种协议共用）。
 *
 * 只负责"行 → 帧"，不懂业务字段。规则：
 * - 空行分隔帧；同一帧的多行 `data:` 用 `\n` 拼接；
 * - 忽略注释行（`:` 开头）与未知字段；
 * - `data: [DONE]`（OpenAI）作为终止信号；
 * - 流结束时若还有未闭合的数据，补发一帧（部分网关不补最后空行）。
 *
 * 不假设 CRLF：`readUtf8Line()` 会去掉行尾的 `\r`。
 */
class SseReader(private val source: LineSource) {

    suspend fun read(onFrame: suspend (SseFrame) -> Unit) {
        var event: String? = null
        val buffer = StringBuilder()

        while (true) {
            val line = source.readLine() ?: break
            when {
                line.isEmpty() -> {
                    if (buffer.isNotEmpty()) {
                        val frame = SseFrame(event, buffer.toString())
                        event = null
                        buffer.clear()
                        if (frame.data.trim() == DONE) return
                        onFrame(frame)
                    } else {
                        event = null
                    }
                }

                line.startsWith(":") -> Unit // 心跳/注释

                line.startsWith("event:") ->
                    event = line.substringAfter("event:").trim()

                line.startsWith("data:") -> {
                    val chunk = line.substringAfter("data:").removePrefix(" ")
                    if (buffer.isNotEmpty()) buffer.append('\n')
                    buffer.append(chunk)
                }

                else -> Unit // id: / retry: 等字段与对话无关
            }
        }

        if (buffer.isNotEmpty()) {
            val frame = SseFrame(event, buffer.toString())
            if (frame.data.trim() != DONE) onFrame(frame)
        }
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}

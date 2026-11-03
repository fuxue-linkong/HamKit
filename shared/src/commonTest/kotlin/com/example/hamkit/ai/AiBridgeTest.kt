package com.example.hamkit.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 桥接层的编解码必须稳定：iOS 的历史文件一旦写坏，用户的对话就丢了。 */
class AiBridgeTest {

    @Test
    fun `config json round trip keeps every field`() {
        val config = AiConfig(
            protocol = AiProtocol.ANTHROPIC_MESSAGES,
            preset = AiPreset.ANTHROPIC,
            baseUrl = "https://example.com",
            model = "claude-test",
            temperature = 1.2,
            maxOutputTokens = 4096,
            saveHistory = false,
            historyLimit = 50,
            systemPrompt = "自定义提示词",
            disabledTools = "noop,get_weather",
            confirmSideEffects = false,
            maxToolRounds = 3,
        )
        val restored = AiBridge.configFromJson(AiBridge.configToJson(config))
        assertEquals(AiProtocol.ANTHROPIC_MESSAGES, restored.protocol)
        assertEquals("https://example.com", restored.baseUrl)
        assertEquals("claude-test", restored.model)
        assertEquals(4096, restored.maxOutputTokens)
        assertEquals(false, restored.saveHistory)
        assertEquals("自定义提示词", restored.systemPrompt)
        assertEquals("noop,get_weather", restored.disabledTools)
        assertEquals(false, restored.confirmSideEffects)
        assertEquals(3, restored.maxToolRounds)
    }

    @Test
    fun `config json with junk falls back to defaults`() {
        val restored = AiBridge.configFromJson("{not json")
        assertEquals(AiPreset.DEEPSEEK, restored.preset)
        assertEquals("deepseek-chat", restored.model)
    }

    @Test
    fun `messages json round trip keeps tool calls and results`() {
        val messages = listOf(
            AiMessage.user("查 ISS 过境"),
            AiMessage(
                role = AiRole.ASSISTANT,
                text = "",
                calls = listOf(AiToolCall("call_1", "get_satellite_passes", """{"satellite":"ISS"}""")),
            ),
            AiMessage.toolResult("call_1", "get_satellite_passes", """{"passes":[]}""", isError = false),
            AiMessage.assistant("没有过境"),
        )
        val restored = AiBridge.messagesFromJson(AiBridge.messagesToJson(messages))
        assertEquals(4, restored.size)
        assertEquals("call_1", restored[1].calls.first().id)
        assertEquals("get_satellite_passes", restored[2].toolName)
        assertEquals("没有过境", restored[3].text)
    }

    @Test
    fun `conversation title uses first user message`() {
        val messages = listOf(AiMessage.user("帮我算一下 ISS 今晚什么时候过境，需要多少仰角"))
        val title = AiBridge.conversationTitle(messages)
        assertTrue(title.endsWith("…"))
        assertEquals("新对话", AiBridge.conversationTitle(emptyList()))
    }

    @Test
    fun `preset metadata is exposed for settings ui`() {
        assertTrue(AiBridge.presetNames().contains("DEEPSEEK"))
        assertEquals("DeepSeek", AiBridge.presetLabel("DEEPSEEK"))
        assertEquals("OPENAI_CHAT", AiBridge.presetProtocol("DEEPSEEK"))
        assertEquals("deepseek-chat", AiBridge.presetDefaultModel("DEEPSEEK"))
        assertEquals("Chat Completions", AiBridge.protocolLabel("OPENAI_CHAT"))
    }
}

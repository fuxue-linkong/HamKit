package com.example.hamkit.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 模型列表解析：三种协议各一种响应格式。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AiModelListTest {

    private val config = AiConfig(
        protocol = AiProtocol.OPENAI_CHAT,
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
    )

    @Test
    fun `openai models response`() {
        val models = AiModelList.parseModels(
            AiProtocol.OPENAI_CHAT,
            """{"object":"list","data":[{"id":"deepseek-reasoner"},{"id":"deepseek-chat"}]}""",
        )
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models)
    }

    @Test
    fun `anthropic models response`() {
        val models = AiModelList.parseModels(
            AiProtocol.ANTHROPIC_MESSAGES,
            """{"data":[{"id":"claude-sonnet-4-5","type":"model"}]}""",
        )
        assertEquals(listOf("claude-sonnet-4-5"), models)
    }

    @Test
    fun `gemini models response strips prefix`() {
        val models = AiModelList.parseModels(
            AiProtocol.GEMINI,
            """{"models":[{"name":"models/gemini-2.5-flash"},{"name":"models/gemini-2.5-pro"}]}""",
        )
        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), models)
    }

    @Test
    fun `malformed or empty responses`() {
        assertTrue(AiModelList.parseModels(AiProtocol.OPENAI_CHAT, "not json").isEmpty())
        assertTrue(AiModelList.parseModels(AiProtocol.OPENAI_CHAT, "{}").isEmpty())
        assertTrue(AiModelList.parseModels(AiProtocol.GEMINI, """{"models":[]}""").isEmpty())
    }

    @Test
    fun `models url per protocol`() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            AiModelList.modelsUrl(config),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            AiModelList.modelsUrl(config.copy(protocol = AiProtocol.ANTHROPIC_MESSAGES, baseUrl = "https://api.anthropic.com")),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            AiModelList.modelsUrl(
                config.copy(
                    protocol = AiProtocol.GEMINI,
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                )
            ),
        )
    }
}

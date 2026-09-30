package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.dlucxoket.lucatbot.bot.llm.PromptImage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * LLM.DEFAULT 三参转发守卫（第四轮 OOP 审查 P1-1）
 *
 * 生产链路 `LoopConfig → ConversationAgent()` 默认注入 [LLM.DEFAULT]——
 * 图片附件必须穿过委托对象到达真实实现。曾经的断链：委托对象只 override
 * 两参，三参走接口默认实现**先丢 images 再调两参**，`OpenAIAPI` 的多模态
 * override 永远走不到；而 FakeLLM 直接 override 三参、ImageChannelTest
 * 直测 OpenAIAPI，全都绕过 DEFAULT——测试全绿掩盖了生产图片通道整体失效。
 *
 * 故本文件必须经 [LLM.DEFAULT] 实测转发，不允许用替身绕开被测对象。
 */
class LlmDefaultForwardingTest {

    /** 记录三参调用收到的图片；若被降级成两参纯文本即为断链证据 */
    private class RecordingLlm : LLM {
        var images: List<PromptImage>? = null
        var plainCalls = 0

        override fun ask(systemPrompt: String, question: String): String {
            plainCalls++
            return """{"action":"return","reason":"plain"}"""
        }

        override fun ask(systemPrompt: String, question: String, images: List<PromptImage>): String {
            this.images = images
            return """{"action":"return","reason":"images"}"""
        }
    }

    @AfterEach
    fun tearDown() {
        // 清除安装，避免替身残留在同 JVM 污染后续用例（install(null) 即为此设）
        LLM.install(null)
    }

    @Test
    fun `DEFAULT forwards three-arg ask with images to installed instance`() {
        val fake = RecordingLlm()
        LLM.install(fake)
        val imgs = listOf(PromptImage("a.png", "data:image/png;base64,AA"))

        val out = LLM.DEFAULT.ask("sys", "q", imgs)

        assertEquals("""{"action":"return","reason":"images"}""", out, "三参结果应来自真实实现")
        assertEquals(imgs, fake.images, "图片必须穿过 DEFAULT 到达真实实现")
        assertEquals(0, fake.plainCalls, "不允许降级成两参纯文本")
    }

    @Test
    fun `conversation agent default wiring delivers images through DEFAULT`() {
        // 生产构造：ConversationAgent() 默认 llm = LLM.DEFAULT（LoopConfig:93 同款）
        val fake = RecordingLlm()
        LLM.install(fake)
        val agent = ConversationAgent()
        val request = ConversationRequest(
            images = listOf(PromptImage("a.png", "data:image/png;base64,AA"))
        )

        agent.processObject(request)

        assertEquals(
            request.images, fake.images,
            "生产默认接线（Agent 默认参数 → DEFAULT → 已安装实例）必须送达图片"
        )
        assertEquals(0, fake.plainCalls, "三参调用不得被降级")
    }
}

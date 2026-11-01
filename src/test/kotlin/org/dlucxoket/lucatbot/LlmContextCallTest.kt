package org.dlucxoket.lucatbot

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.UserMessage
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.dlucxoket.lucatbot.bot.llm.LlmRole
import org.dlucxoket.lucatbot.bot.llm.LlmTurn
import org.dlucxoket.lucatbot.bot.llm.OpenAIAPI
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * API 原生多轮上下文（`askWithContext`）守卫
 *
 * 两层防线，与图片通道 P1-1 同一套路：
 * 1. **DEFAULT 转发**：历史必须穿过 [LLM.DEFAULT] 委托对象到达真实实现——
 *    委托对象漏 override 时，历史会走接口默认的「折叠成一条 user 文本」
 *    降级路径，测试替身全绿也掩盖生产上下文白带；
 * 2. **消息组装**：[OpenAIAPI.contextMessages] 的顺序必须是
 *    system → 历史（正序）→ 本轮 user，且 assistant 轮必须是 AiMessage
 *    （错认成 user 会让模型分不清「谁说的」）。
 */
class LlmContextCallTest {

    /** 记录上下文调用是否原样到达真实实现；被降级成两参折叠即为断链证据 */
    private class RecordingLlm : LLM {
        var history: List<LlmTurn>? = null
        var plainCalls = 0

        override fun ask(systemPrompt: String, question: String): String {
            plainCalls++
            return "plain"
        }

        override fun askWithContext(systemPrompt: String, history: List<LlmTurn>, question: String): String {
            this.history = history
            return "context"
        }
    }

    @AfterEach
    fun tearDown() {
        LLM.install(null)
    }

    @Test
    fun `DEFAULT forwards askWithContext without folding history`() {
        val fake = RecordingLlm()
        LLM.install(fake)
        val turns = listOf(LlmTurn(LlmRole.USER, "你好"), LlmTurn(LlmRole.ASSISTANT, "嗯嗯"))

        val out = LLM.DEFAULT.askWithContext("sys", turns, "在吗")

        assertEquals("context", out, "结果应来自真实实现")
        assertEquals(turns, fake.history, "历史必须原样穿过 DEFAULT，不许折叠")
        assertEquals(0, fake.plainCalls, "不允许降级成两参纯文本")
    }

    @Test
    fun `interface default impl folds history so test doubles stay compatible`() {
        // 只实现两参的替身（FakeLLM 同款）——接口默认实现应把历史折进 user 文本
        val folding = object : LLM {
            override fun ask(systemPrompt: String, question: String): String = question
        }
        val out = folding.askWithContext(
            "sys",
            listOf(LlmTurn(LlmRole.USER, "一问"), LlmTurn(LlmRole.ASSISTANT, "一答")),
            "二问"
        )
        assertEquals("user：一问\nassistant：一答\nuser：二问", out, "降级折叠：历史逐行 + 本轮 user")
    }

    @Test
    fun `context messages follow system, history, question order`() {
        val msgs = OpenAIAPI().contextMessages(
            "sys",
            listOf(LlmTurn(LlmRole.USER, "一问"), LlmTurn(LlmRole.ASSISTANT, "一答")),
            "二问"
        )

        assertEquals(4, msgs.size)
        assertTrue(msgs[0] is SystemMessage, "第 1 条应为 system")
        assertEquals("sys", (msgs[0] as SystemMessage).text())
        assertTrue(msgs[1] is UserMessage, "历史第 1 轮应为 user")
        assertEquals("一问", (msgs[1] as UserMessage).singleText())
        assertTrue(msgs[2] is AiMessage, "历史第 2 轮必须是 assistant——错认成 user 会让模型分不清谁说的")
        assertEquals("一答", (msgs[2] as AiMessage).text())
        assertTrue(msgs[3] is UserMessage, "最后一条应为本轮 user")
        assertEquals("二问", (msgs[3] as UserMessage).singleText())
    }

    @Test
    fun `blank system prompt sends no system message`() {
        val msgs = OpenAIAPI().contextMessages("", listOf(LlmTurn(LlmRole.USER, "一问")), "二问")
        assertEquals(2, msgs.size, "systemPrompt 为空时不应发空 system 消息")
        assertTrue(msgs.none { it is SystemMessage })
    }

    @Test
    fun `history system turn is kept even when systemPrompt is blank`() {
        val msgs = OpenAIAPI().contextMessages("", listOf(LlmTurn(LlmRole.SYSTEM, "历史里的系统轮")), "二问")
        assertEquals(2, msgs.size, "历史 1 轮 + 本轮 = 2 条")
        assertTrue(msgs[0] is SystemMessage, "历史里显式给的 system 轮照发，不校验、交调用方决定")
        assertTrue(msgs[1] is UserMessage, "最后一条是本轮 user")
    }

    @Test
    fun `renderRequest expands history roles verbatim for the trace`() {
        val text = OpenAIAPI().renderRequest(
            "sys",
            listOf(LlmTurn(LlmRole.USER, "一问"), LlmTurn(LlmRole.ASSISTANT, "一答")),
            "二问"
        )
        assertEquals("### system\nsys\n### user\n一问\n### assistant\n一答\n### user\n二问", text)
    }

    @Test
    fun `assembled user message keeps content as TextContent`() {
        // 冒烟：组装出来的 UserMessage 取文本的方式与 ImageChannelTest 同源（TextContent）
        val msg = OpenAIAPI().contextMessages("", listOf(LlmTurn(LlmRole.USER, "一问")), "二问").last()
        val text = (msg as UserMessage).contents().first() as TextContent
        assertEquals("二问", text.text())
    }
}

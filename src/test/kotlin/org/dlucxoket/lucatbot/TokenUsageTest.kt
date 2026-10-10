package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.llm.InMemoryTokenUsageStore
import org.dlucxoket.lucatbot.bot.llm.JsonlTokenUsageStore
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.dlucxoket.lucatbot.bot.llm.ModelPricing
import org.dlucxoket.lucatbot.bot.llm.TokenUsageAggregator
import org.dlucxoket.lucatbot.bot.llm.TokenUsageChannel
import org.dlucxoket.lucatbot.bot.llm.TokenUsagePending
import org.dlucxoket.lucatbot.bot.llm.TokenUsageScope
import org.dlucxoket.lucatbot.bot.llm.TokenUsageStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * token 用量聚合器：**按维度分门别类**（通道 文字/图片 × agent × 会话 × 模型）
 *
 * 覆盖四件事：三维分类汇总的正确性、存储读回的「跨重启累计」、未返回用量的
 * 如实单列、维度上下文的挂载/还原。**每条测试 new 自己的实例 + 替身存储**——
 * 不再靠 configure/reset 改全局状态（构造注入修复后的写法）。
 */
class TokenUsageTest {

    /** 建一个不打自动汇总日志的聚合器（every = 0） */
    private fun aggregator(
        store: TokenUsageStore = InMemoryTokenUsageStore(),
        every: Int = 0
    ) = TokenUsageAggregator(store, every)

    @Test
    fun `summary groups by channel agent and conversation`() {
        val ledger = aggregator()
        ledger.record("ConversationAgent", "private:123", TokenUsageChannel.TEXT, "m", 100, 20)
        ledger.record("ConversationAgent", "private:123", TokenUsageChannel.IMAGE, "m", 900, 30)
        ledger.record("SessionPickerAgent", "消息列表", TokenUsageChannel.TEXT, "m", 50, 10)

        val s = ledger.summary()
        // 总账
        assertTrue(s.contains("累计 3 次：入 1050 / 出 60 / 合计 1110"), s)
        // 维度一：通道（图片的视觉 token 计在入参里，靠通道单列出来）
        assertTrue(s.contains("按通道：文字 2次 入150/出30/合180｜图片 1次 入900/出30/合930"), s)
        // 维度二：agent
        assertTrue(s.contains("按 agent：ConversationAgent 2次 入1000/出50/合1050｜SessionPickerAgent 1次 入50/出10/合60"), s)
        // 维度三：会话
        assertTrue(s.contains("按会话：private:123 2次"), s)
        assertTrue(s.contains("消息列表 1次"), s)
        // 维度四：模型（换端点后成本可比）
        assertTrue(s.contains("按模型：m 3次"), s)
    }

    @Test
    fun `conversation list is capped with an other bucket`() {
        val ledger = aggregator()
        repeat(12) { i ->
            ledger.record("ConversationAgent", "private:$i", TokenUsageChannel.TEXT, "m", 1, 1)
        }
        val s = ledger.summary(topConversations = 3)
        assertTrue(s.contains("其他 9 个会话"), "超出前 N 的会话归并：$s")
        assertTrue(s.contains("private:0") && s.contains("private:1") && s.contains("private:2"),
            "按合计排序取前 3：$s")
    }

    @Test
    fun `persisted rows reload so summary is cumulative across restarts`() {
        val file = File.createTempFile("token-usage", ".jsonl").apply { delete() }
        try {
            val first = aggregator(JsonlTokenUsageStore(file.absolutePath))
            first.record("ConversationAgent", "private:1", TokenUsageChannel.TEXT, "m", 10, 5)

            // 落盘行带齐维度字段
            assertTrue(file.exists(), "应落盘 jsonl")
            val line = file.readLines().single()
            assertTrue(line.contains("\"agent\":\"ConversationAgent\""), line)
            assertTrue(line.contains("\"channel\":\"文字\""), line)
            assertTrue(line.contains("\"conversation\":\"private:1\""), line)
            assertTrue(line.contains("\"epoch\":"), "时间轴分桶要 epoch：$line")

            // 模拟重启：新实例读同一存储 → 汇总跨重启累计
            val restarted = aggregator(JsonlTokenUsageStore(file.absolutePath))
            restarted.record("ConversationAgent", "private:1", TokenUsageChannel.TEXT, "m", 1, 1)
            val s = restarted.summary()
            assertTrue(s.contains("累计 2 次：入 11 / 出 6 / 合计 17"), "跨重启累计：$s")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `trace fields request response duration round trip and detail lookup by id`() {
        val ledger = aggregator() // InMemory：id = 自增序号
        ledger.record(
            "ConversationAgent", "private:1", TokenUsageChannel.TEXT, "m", 10, 5,
            action = "return", durationMs = 880,
            request = "### system\n你是牢洛\n### user\n聊天记录…",
            response = """{"action":"return"}"""
        )
        ledger.record("SessionPickerAgent", "消息列表", TokenUsageChannel.TEXT, "m", 50, 10)

        // 明细列表：带 id/耗时（正文由存储决定，详情才必带）
        val rows = ledger.history(10)
        assertEquals(2, rows.size)
        val traced = rows.first { it.action == "return" }
        assertEquals(880L, traced.durationMs, "耗时随行入库")
        assertEquals("### system\n你是牢洛\n### user\n聊天记录…", traced.request)

        // 详情按 id 取（轨迹面板点开的那一次）
        val detail = ledger.findCall(traced.id)
        assertNotNull(detail, "按 id 取详情")
        assertEquals("""{"action":"return"}""", detail!!.response)
        assertEquals("### system\n你是牢洛\n### user\n聊天记录…", detail.request)
        assertEquals(880L, detail.durationMs)
        assertNull(ledger.findCall(999_999L), "查不到 = null（前端转 404）")
    }

    @Test
    fun `cached tokens accumulate in summary and survive reload`() {
        // prompt 缓存命中（langchain4j 1.x 的 cached_tokens）：入参子集，
        // 汇总单列一行、跨重启读回仍累计；端点不回的行不计
        val file = File.createTempFile("token-cached", ".jsonl").apply { delete() }
        try {
            val first = aggregator(JsonlTokenUsageStore(file.absolutePath))
            first.record(
                "ConversationAgent", "private:1", TokenUsageChannel.TEXT, "m", 1000, 50,
                cached = 640
            )
            first.record("ConversationAgent", "private:1", TokenUsageChannel.TEXT, "m", 1000, 50)

            assertEquals(640L, first.cachedTokens())
            val s = first.summary()
            assertTrue(s.contains("缓存命中：640 token（占入参 32%）"), "汇总行：$s")

            // 模拟重启：明细行带 cached、读回继续累计
            val restarted = aggregator(JsonlTokenUsageStore(file.absolutePath))
            assertEquals(640L, restarted.cachedTokens(), "缓存命中跨重启累计")
            assertEquals(640L, restarted.findCall(1L)?.cached, "明细行保留 cached")
            assertNull(restarted.findCall(2L)?.cached, "端点不回 = null（如实不猜）")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `pricing estimate splits cached input and stays null when unconfigured`() {
        // 费用估算：读侧现算、没配单价不算不猜；缓存命中段按缓存价折算
        assertNull(ModelPricing().estimate(1_000_000, 500_000, cached = 400_000), "没配价 = null 不猜")

        val p = ModelPricing(inputPer1M = 2.0, outputPer1M = 8.0, cachedPer1M = 0.2)
        // 1M 入参其中 0.4M 命中缓存 + 0.5M 出参：0.6*2 + 0.4*0.2 + 0.5*8 = 5.28
        assertEquals(5.28, p.estimate(1_000_000, 500_000, cached = 400_000)!!, 1e-9)

        // 没配缓存价 → 命中段按入参价（不打折）：1*2 + 0.5*8 = 6
        val noCachePrice = ModelPricing(inputPer1M = 2.0, outputPer1M = 8.0)
        assertEquals(6.0, noCachePrice.estimate(1_000_000, 500_000, cached = 400_000)!!, 1e-9)

        // 异常数据（cached > input）按入参截断：命中最多算满 1M
        assertEquals(4.2, p.estimate(1_000_000, 500_000, cached = 9_999_999)!!, 1e-9)
    }

    @Test
    fun `missing usage counts the call with zero and is called out honestly`() {
        val ledger = aggregator()
        ledger.record("ConversationAgent", "private:1", TokenUsageChannel.TEXT, "m", null, null)
        val s = ledger.summary()
        assertTrue(s.contains("累计 1 次：入 0 / 出 0 / 合计 0"), s)
        assertTrue(s.contains("1 次端点未返回用量（按 0 计）"), "不猜数、如实单列：$s")
        assertEquals(1L, ledger.noUsageCalls())
    }

    @Test
    fun `scope sets dimensions and restores previous value`() {
        assertEquals("-", TokenUsageScope.conversation(), "默认会话维度")

        val seen = TokenUsageScope.withAgent("ConversationAgent") {
            TokenUsageScope.withConversation("private:123") {
                "${TokenUsageScope.agent()}|${TokenUsageScope.conversation()}"
            }
        }
        assertEquals("ConversationAgent|private:123", seen)

        // 嵌套后还原外层，不污染同线程后续调用
        TokenUsageScope.withAgent("MemoryAgent") {
            assertEquals("MemoryAgent", TokenUsageScope.agent())
            TokenUsageScope.withAgent("ConversationAgent") {
                assertEquals("ConversationAgent", TokenUsageScope.agent())
            }
            assertEquals("MemoryAgent", TokenUsageScope.agent(), "内层执行完还原外层")
        }
        assertEquals("unknown", TokenUsageScope.agent(), "顶层执行完回到默认")
        assertEquals("-", TokenUsageScope.conversation())
    }

    @Test
    fun `decision action and entry round are recorded with the usage`() {
        // P5 核心链路：端点只回 usage（暂存）→ Agent 出来时带**决策标签**统一记账——
        // 只有问的人知道这一问得出了 send/idle，传输层补标就得改写 append-only 明细
        val llm = UsageFakeLLM(mutableListOf("""{"action":"send","params":{"content":"在吗"}}"""))
        val ledger = TokenUsageAggregator(InMemoryTokenUsageStore(), summaryEvery = 0)

        ConversationAgent(llm, tokenUsage = ledger).processObject(
            ConversationRequest(conversationId = "private:1", entryRound = true)
        )

        val row = ledger.history().single()
        assertEquals("send", row.action, "动作标签来自 Agent 的 usageAction 钩子")
        assertTrue(row.firstRound, "进场首轮标记来自入参 entryRound")
        assertEquals("private:1", row.conversation, "会话维度从入参自推")
        assertEquals("ConversationAgent", row.agent)
        assertEquals("test-model", row.model)
        assertEquals(1000L, row.input)

        // 两个新维度都能进汇总
        val s = ledger.summary()
        assertTrue(s.contains("按动作：send 1次"), "按动作分组：$s")
        assertTrue(s.contains("进场首轮：1 次"), "进场首轮统计：$s")
        assertEquals(1L, ledger.firstRoundCalls())
    }
}

/** 假 LLM：回答前先往暂存里塞一条用量（模拟真实端点回 tokenUsage 的时序） */
private class UsageFakeLLM(private val responses: MutableList<String>) : LLM {
    override fun ask(systemPrompt: String, question: String): String {
        TokenUsageScope.stashUsage(
            TokenUsagePending(channel = TokenUsageChannel.TEXT, model = "test-model", input = 1_000, output = 50)
        )
        return if (responses.isEmpty()) """{"action":"return"}""" else responses.removeAt(0)
    }

    override fun ask(
        systemPrompt: String,
        question: String,
        images: List<org.dlucxoket.lucatbot.bot.llm.PromptImage>
    ): String = ask(systemPrompt, question)
}

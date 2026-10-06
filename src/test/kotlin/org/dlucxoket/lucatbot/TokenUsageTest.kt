package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.llm.TokenUsageLedger
import org.dlucxoket.lucatbot.bot.llm.TokenUsageScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * token 用量账本：**按维度分门别类**（通道 文字/图片 × agent × 会话）
 *
 * 覆盖三件事：三维分类汇总的正确性、jsonl 落盘与「跨重启累计」、
 * 维度上下文的挂载/还原（断链会把统计全记成 unknown/-）。
 */
class TokenUsageTest {

    private lateinit var file: File

    @BeforeEach
    fun setUp() {
        file = File.createTempFile("token-usage", ".jsonl").apply { delete() }
        TokenUsageLedger.summaryEvery = 0 // 测试期间不打自动汇总日志
        TokenUsageLedger.configure(file.absolutePath)
        TokenUsageLedger.reset()
    }

    @AfterEach
    fun tearDown() {
        TokenUsageLedger.configure(null)
        TokenUsageLedger.reset()
        TokenUsageLedger.summaryEvery = 10
        file.delete()
    }

    @Test
    fun `summary groups by channel agent and conversation`() {
        TokenUsageLedger.record("ConversationAgent", "private:123", TokenUsageLedger.Channel.TEXT, "m", 100, 20)
        TokenUsageLedger.record("ConversationAgent", "private:123", TokenUsageLedger.Channel.IMAGE, "m", 900, 30)
        TokenUsageLedger.record("SessionPickerAgent", "消息列表", TokenUsageLedger.Channel.TEXT, "m", 50, 10)

        val s = TokenUsageLedger.summary()
        // 总账
        assertTrue(s.contains("累计 3 次：入 1050 / 出 60 / 合计 1110"), s)
        // 维度一：通道（图片的视觉 token 计在入参里，靠通道单列出来）
        assertTrue(s.contains("按通道：文字 2次 入150/出30/合180｜图片 1次 入900/出30/合930"), s)
        // 维度二：agent
        assertTrue(s.contains("按 agent：ConversationAgent 2次 入1000/出50/合1050｜SessionPickerAgent 1次 入50/出10/合60"), s)
        // 维度三：会话
        assertTrue(s.contains("按会话：private:123 2次"), s)
        assertTrue(s.contains("消息列表 1次"), s)
    }

    @Test
    fun `conversation list is capped with an other bucket`() {
        repeat(12) { i ->
            TokenUsageLedger.record("ConversationAgent", "private:$i", TokenUsageLedger.Channel.TEXT, "m", 1, 1)
        }
        val s = TokenUsageLedger.summary(topConversations = 3)
        assertTrue(s.contains("其他 9 个会话"), "超出前 N 的会话归并：$s")
        assertTrue(s.contains("private:0") && s.contains("private:1") && s.contains("private:2"),
            "按合计排序取前 3：$s")
    }

    @Test
    fun `persisted lines reload so summary is cumulative across restarts`() {
        TokenUsageLedger.record("ConversationAgent", "private:1", TokenUsageLedger.Channel.TEXT, "m", 10, 5)

        assertTrue(file.exists(), "应落盘 jsonl")
        val line = file.readLines().single()
        assertTrue(line.contains("\"agent\":\"ConversationAgent\""), line)
        assertTrue(line.contains("\"channel\":\"文字\""), line)
        assertTrue(line.contains("\"conversation\":\"private:1\""), line)

        // 模拟重启：内存清空后重新从文件加载 → 汇总跨重启累计
        TokenUsageLedger.reset()
        TokenUsageLedger.record("ConversationAgent", "private:1", TokenUsageLedger.Channel.TEXT, "m", 1, 1)
        val s = TokenUsageLedger.summary()
        assertTrue(s.contains("累计 2 次：入 11 / 出 6 / 合计 17"), "跨重启累计：$s")
    }

    @Test
    fun `missing usage counts the call with zero and is called out honestly`() {
        TokenUsageLedger.record("ConversationAgent", "private:1", TokenUsageLedger.Channel.TEXT, "m", null, null)
        val s = TokenUsageLedger.summary()
        assertTrue(s.contains("累计 1 次：入 0 / 出 0 / 合计 0"), s)
        assertTrue(s.contains("1 次端点未返回用量（按 0 计）"), "不猜数、如实单列：$s")
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
}

package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.MemoryKeywordAgent
import org.dlucxoket.lucatbot.bot.agent.data.MemoryKeywordRequest
import org.dlucxoket.lucatbot.bot.agent.data.MemoryKeywordResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 关键词拆分智能体（P2：进场记忆预搜的前置）
 *
 * 三件事：拆词解析（去重/封顶）、**解析失败退化为空**（预搜绝不阻断进场）、
 * system/user 指令与剧情数据分家（AGENTS 的 prompt 拆分约定）。
 */
class MemoryKeywordAgentTest {

    @Test
    fun `parse dedupes trims and caps keywords`() {
        val result = MemoryKeywordResult.parse(
            """{"keywords": [" 蓝色 ", "蓝色", "张三", "", "  ", "洛洛"]}"""
        )
        assertEquals(listOf("蓝色", "张三", "洛洛"), result.keywords, "去重 + 去空白 + 保序")

        val many = MemoryKeywordResult.parse(
            """{"keywords": [${(1..20).joinToString(",") { "\"词$it\"" }}]}"""
        )
        assertEquals(MemoryKeywordResult.MAX_KEYWORDS, many.keywords.size, "封顶防结果段撑爆")
    }

    @Test
    fun `garbage or missing keywords degrades to empty so entry never blocks`() {
        assertEquals(emptyList<String>(), MemoryKeywordResult.parse("我不是JSON").keywords)
        assertEquals(emptyList<String>(), MemoryKeywordResult.parse("""{"action":"send"}""").keywords)
        assertEquals(emptyList<String>(), MemoryKeywordResult.parse("""{"keywords": "蓝色"}""").keywords,
            "非数组形态不猜，退空")
        assertEquals(emptyList<String>(), MemoryKeywordResult.parse("").keywords)
    }

    @Test
    fun `prompt keeps instructions in system and chat window in user`() {
        val llm = FakeLLM(mutableListOf("""{"keywords": []}"""))
        val result = MemoryKeywordAgent(llm).processObject(
            MemoryKeywordRequest(conversationContent = "张三：明天去上海\n李四：好啊")
        )

        assertTrue(result.keywords.isEmpty(), "空词表 = 不预搜，照常进场")
        val prompt = llm.prompts.single()
        // system：拆词指令
        assertTrue(prompt.contains("独立可搜"), "拆词规则（单个词、不要整句）在 prompt：$prompt")
        assertTrue(prompt.contains("宁多勿少"), "宁多勿少（搜不到没代价、漏搜才致命）：$prompt")
        assertTrue(prompt.contains("keywords"), "输出结构是 keywords 数组")
        // user：剧情数据（聊天记录）
        assertTrue(prompt.contains("以下是当前会话的聊天记录"), "聊天记录在 user 段：$prompt")
        assertTrue(prompt.contains("张三：明天去上海"), "窗口原文进来：$prompt")
        assertTrue(prompt.contains("李四：好啊"), "窗口原文进来：$prompt")
    }
}

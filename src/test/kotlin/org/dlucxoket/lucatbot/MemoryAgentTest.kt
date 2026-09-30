package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectItem
import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectRequest
import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectResult
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** MemoryAgent 的解析与容错（FakeLLM 见 ConversationControllerTest.kt） */
class MemoryAgentTest {

    @Test
    fun `parse reads memories array with kinds and tags`() {
        val result = MemoryReflectResult.parse(
            """{"memories":[
                {"kind":"fact","content":"他养了一只猫","tags":["宠物","喜好"]},
                {"kind":"opinion","content":"说话很有趣"},
                {"kind":"event","content":"约好周五打球"}
            ]}"""
        )
        assertEquals(3, result.memories.size)
        assertEquals("fact", result.memories[0].kind)
        assertEquals("他养了一只猫", result.memories[0].content)
        assertEquals(listOf("宠物", "喜好"), result.memories[0].tags)
        assertEquals("opinion", result.memories[1].kind)
        assertTrue(result.memories[1].tags.isEmpty())
        assertEquals("event", result.memories[2].kind)
    }

    @Test
    fun `parse drops blank content and non-object entries`() {
        val result = MemoryReflectResult.parse(
            """{"memories":[{"kind":"fact","content":"   "},{"kind":"fact","content":"有效条目"},42,null]}"""
        )
        assertEquals(1, result.memories.size)
        assertEquals("有效条目", result.memories[0].content)
    }

    @Test
    fun `parse garbage or missing memories returns empty`() {
        assertTrue(MemoryReflectResult.parse("totally not json").memories.isEmpty())
        assertTrue(MemoryReflectResult.parse("""{"action":"return"}""").memories.isEmpty())
        assertTrue(MemoryReflectResult.parse("""{"memories":"nope"}""").memories.isEmpty())
        assertTrue(MemoryReflectResult.parse("").memories.isEmpty())
    }

    @Test
    fun `handle returns empty on llm garbage without throwing`() {
        val agent = MemoryAgent(FakeLLM(mutableListOf("totally not json")))
        val result = agent.processObject(MemoryReflectRequest(conversationContent = "对话内容"))
        assertTrue(result.memories.isEmpty())
    }

    @Test
    fun `handle returns empty when llm throws`() {
        val agent = MemoryAgent(object : LLM {
            override fun ask(systemPrompt: String, question: String): String =
                throw IllegalStateException("boom")
        })
        val result = agent.processObject(MemoryReflectRequest(conversationContent = "对话内容"))
        assertTrue(result.memories.isEmpty(), "LLM 异常必须兜底为空结果，绝不抛出")
    }

    @Test
    fun `prompt explains memorized marker and memory dates`() {
        // 实盘：昨天的对话被无日期记忆当成当前剧情续话 + 同一段对话被改写复读
        val llm = FakeLLM(mutableListOf("""{"memories":[]}"""))
        MemoryAgent(llm).processObject(MemoryReflectRequest(conversationContent = "对话"))
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("（已记忆）"), "已记忆标记规则：$prompt")
        assertTrue(prompt.contains("已经记过了"), "点明标记语义：$prompt")
        assertTrue(prompt.contains("换个说法复读也算重复"), "堵改写式重复：$prompt")
        assertTrue(prompt.contains("（记于MM-DD）是写入日期"), "记忆日期规则：$prompt")
        assertTrue(prompt.contains("不是刚发生的"), "点明旧记忆非当前剧情")
    }

    @Test
    fun `prompt contains conversation content and existing memory`() {
        val llm = FakeLLM(mutableListOf("""{"memories":[]}"""))
        val agent = MemoryAgent(llm)
        agent.processObject(
            MemoryReflectRequest(
                conversationContent = "我下周要去北京出差",
                existingMemory = "关于此人的记忆：\n事实：\n- 他在上海工作",
                timeHint = "2024-05-01 14:02"
            )
        )
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("我下周要去北京出差"), prompt)
        assertTrue(prompt.contains("他在上海工作"), prompt)
        assertTrue(prompt.contains("2024-05-01 14:02"), prompt)
        assertTrue(prompt.contains("fact|opinion|event"), prompt)
    }

    @Test
    fun `handle maps llm output to result via processObject`() {
        val llm = FakeLLM(
            mutableListOf(
                """{"memories":[{"kind":"fact","content":"他叫小明","tags":["名字"]}]}"""
            )
        )
        val agent = MemoryAgent(llm)
        val result = agent.processObject(MemoryReflectRequest(conversationContent = "他叫我小明，我叫他小明"))
        assertEquals(
            listOf(MemoryReflectItem(kind = "fact", content = "他叫小明", tags = listOf("名字"))),
            result.memories
        )
    }

    // ------------------------------------------------------------------
    // 阶段2：写入前关键词复核（candidates 非空即复核模式）
    // ------------------------------------------------------------------

    @Test
    fun `parse reads keywords and preserves degraded flag`() {
        val ok = MemoryReflectResult.parse(
            """{"memories":[{"kind":"fact","content":"他养猫","keywords":["猫"]}]}"""
        )
        assertEquals(listOf("猫"), ok.memories.single().keywords)
        assertFalse(ok.degraded, "合法输出不是降级结果")

        val garbage = MemoryReflectResult.parse("totally not json")
        assertTrue(garbage.degraded, "解析失败标记 degraded（调用方据此保守放行/兜底）")
        assertTrue(garbage.memories.isEmpty())
    }

    @Test
    fun `filter pass renders candidates and keyword hits then parses output`() {
        val llm = FakeLLM(mutableListOf(
            """{"memories":[{"kind":"fact","content":"他喜欢的颜色是蓝的","tags":[],"keywords":["颜色"]}]}"""
        ))
        val agent = MemoryAgent(llm)

        val result = agent.processObject(MemoryReflectRequest(
            candidates = listOf(
                MemoryReflectItem(kind = "fact", content = "他喜欢的颜色是蓝的", keywords = listOf("颜色", "蓝色"))
            ),
            matchedMemories = listOf("他最喜欢的颜色是蓝色")
        ))

        assertEquals(1, llm.prompts.size, "复核只走一轮 LLM")
        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("【候选记忆】"), "复核 user 数据段：$prompt")
        assertTrue(prompt.contains("【关键词命中的已有记忆】"), "命中结果段：$prompt")
        assertTrue(prompt.contains("关键词：颜色、蓝色"), "候选带关键词：$prompt")
        assertTrue(prompt.contains("他最喜欢的颜色是蓝色"), "命中已有记忆：$prompt")
        assertTrue(prompt.contains("换个说法"), "复核判定规则（改写式重复）：$prompt")
        assertEquals("他喜欢的颜色是蓝的", result.memories.single().content, "保留项原样解析")
        assertFalse(result.degraded)
    }

    @Test
    fun `filter pass without matched memories returns candidates without llm call`() {
        val llm = FakeLLM(mutableListOf())
        val result = MemoryAgent(llm).processObject(MemoryReflectRequest(
            candidates = listOf(MemoryReflectItem(kind = "fact", content = "新记忆"))
        ))

        assertTrue(llm.prompts.isEmpty(), "零命中没有比对对象，直接放行候选")
        assertEquals(1, result.memories.size)
        assertEquals("新记忆", result.memories.single().content)
        assertFalse(result.degraded)
    }

    @Test
    fun `filter pass llm garbage degrades instead of swallowing candidates`() {
        val llm = FakeLLM(mutableListOf("totally not json"))
        val result = MemoryAgent(llm).processObject(MemoryReflectRequest(
            candidates = listOf(MemoryReflectItem(kind = "fact", content = "候选记忆")),
            matchedMemories = listOf("已有记忆")
        ))

        assertTrue(result.degraded, "复核失败必须标记 degraded，由编排层保守放行候选")
        assertTrue(result.memories.isEmpty())
    }
}

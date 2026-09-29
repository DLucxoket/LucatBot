package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.MemoryReflector
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 记忆写入判重——防「记忆被反复记录」
 *
 * 三个写入口的重复场景：
 * - MemoryExtractor 每次 send 都对「对方最后一条消息」重新正则提取（同一句反复记）
 * - 决策 remember 可多轮输出同一句
 * - 退出整理的 LLM 未必严格去重（且判重视野曾经只有 5/3/5/3 条）
 */
class MemoryDedupeTest {

    private fun service() = MemoryService(
        JsonFileMemoryRepository(File.createTempFile("mem-dedupe", ".json").absolutePath)
    )

    @Test
    fun `remembering same fact twice writes once`() {
        val s = service()
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(1L)).size, "同内容重复写入应被吞掉")
    }

    @Test
    fun `whitespace and case variations count as same memory`() {
        val s = service()
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        s.rememberFact(MemorySubject.user(1L), "他喜欢 猫")
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫 ")  // 尾随空白
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(1L)).size, "归一化后视为同一条")
    }

    @Test
    fun `same content under different kind is still duplicate`() {
        val s = service()
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        s.rememberEvent(MemorySubject.user(1L), "他喜欢猫")
        s.rememberOpinion(MemorySubject.user(1L), "他喜欢猫")
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(1L)).size, "同内容换 kind 也是重复")
    }

    @Test
    fun `different contents are all kept`() {
        val s = service()
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        s.rememberFact(MemorySubject.user(1L), "他喜欢狗")
        assertEquals(2, s.getMemoriesOf(MemorySubject.user(1L)).size)
    }

    @Test
    fun `same content under different subjects both kept`() {
        val s = service()
        s.rememberFact(MemorySubject.user(7L), "他喜欢猫")
        s.rememberFact(MemorySubject.group(7L), "他喜欢猫")
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(7L)).size, "键空间隔离不互相吞")
        assertEquals(1, s.getMemoriesOf(MemorySubject.group(7L)).size)
    }

    @Test
    fun `saveAll filters batch-internal and existing duplicates`() {
        val s = service()
        s.rememberFact(MemorySubject.user(2L), "已有记忆")

        // 批内互重 + 与已有重复 + 新内容
        s.saveAll(listOf(
            item("他喜欢猫"),
            item("他喜欢猫"),   // 批内重复
            item("已有记忆"),   // 与已有重复
            item("新的记忆")    // 唯一新增
        ))
        val contents = s.getMemoriesOf(MemorySubject.user(2L)).map { it.content }.toSet()
        assertEquals(setOf("已有记忆", "他喜欢猫", "新的记忆"), contents)
    }

    @Test
    fun `memory extractor replayed by repeated sends writes once`() {
        // 模拟：bot 连发多条、对方最后一条消息没变 → 同一句被提取多次
        val s = service()
        val subject = MemorySubject.user(3L)
        repeat(3) {
            s.saveAll(org.dlucxoket.lucatbot.bot.memory.MemoryExtractor.extract(subject, "我叫小明"))
        }
        assertEquals(1, s.getMemoriesOf(subject).size, "同一句被反复提取只能留一条")
    }

    @Test
    fun `blank content is never written`() {
        val s = service()
        s.saveAll(listOf(item("  "), item("正常")))
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(2L)).size)
    }

    private fun item(content: String) = MemoryItem(
        userId = 2L, subjectType = MemorySubject.Type.USER,
        type = MemoryItem.MemoryType.FACT, content = content
    )

    // ------------------------------------------------------------------
    // 「已记忆」标记（防退出整理把同一段对话改写一遍又记一遍）
    // ------------------------------------------------------------------

    @Test
    fun `markMemorized annotates summary and is idempotent`() {
        val f = Fixture()
        f.chat.recordHistoryMessage(
            conversationId = "private:5", senderId = 5L, senderName = "甲",
            content = "我叫小明", timestamp = 1_000L, isFromBot = false, messageId = 42L
        )
        // messageId 精确定位
        f.chat.markMemorized("private:5", 42L, "我叫小明")
        assertTrue(f.chat.getHistorySummary("private:5", 10).contains("我叫小明（已记忆）"),
            "摘要行尾带（已记忆）：${f.chat.getHistorySummary("private:5", 10)}")
        // 幂等 + 无目标不动
        f.chat.markMemorized("private:5", 42L, "我叫小明")
        f.chat.markMemorized("private:5", null, "不存在的内容")
        assertEquals(1, f.chat.getHistory("private:5", 10).size, "重复打标不产生新行")
    }

    @Test
    fun `markWindowMemorized marks non bot lines only`() {
        val f = Fixture()
        f.chat.recordHistoryMessage(
            conversationId = "private:6", senderId = 6L, senderName = "甲",
            content = "第一句", timestamp = 1_000L, isFromBot = false
        )
        f.chat.recordHistoryMessage(
            conversationId = "private:6", senderId = 0L, senderName = "bot",
            content = "回你", timestamp = 2_000L, isFromBot = true
        )
        f.chat.markWindowMemorized("private:6", 30)
        val summary = f.chat.getHistorySummary("private:6", 10)
        assertTrue(summary.contains("第一句（已记忆）"), "对方消息被打标：$summary")
        assertFalse(summary.contains("回你（已记忆）"), "机器人发言不是记忆素材，不打标：$summary")
    }

    @Test
    fun `memory summary lines carry write dates`() {
        val repo = JsonFileMemoryRepository(File.createTempFile("mem-date", ".json").absolutePath)
        val s = MemoryService(repo)
        s.rememberFact(MemorySubject.user(3L), "他叫小明")
        // 一年前写入的记忆 → 带年份，LLM 才知道是旧事
        repo.save(MemoryItem(
            userId = 3L, subjectType = MemorySubject.Type.USER,
            type = MemoryItem.MemoryType.FACT, content = "一年前的旧事",
            createdAt = java.time.ZoneId.of("Asia/Shanghai").let { z ->
                java.time.LocalDate.of(java.time.LocalDate.now(z).year - 1, 6, 1)
                    .atStartOfDay(z).toInstant().toEpochMilli()
            }
        ))

        val summary = s.getMemorySummary(MemorySubject.user(3L))
        assertTrue(summary.contains("（记于"), "记忆行带写入日期：$summary")
        assertTrue(summary.contains("- 一年前的旧事（记于"), "旧记忆带日期前缀：$summary")
        assertTrue(
            summary.lines().any { it.contains("一年前的旧事") && Regex("""记于\d{4}-\d{2}-\d{2}）""").containsMatchIn(it) },
            "跨年记忆用 yyyy-MM-dd：$summary"
        )
        assertTrue(summary.contains("他叫小明（记于"), "当年记忆用 MM-dd：$summary")
    }

    private class Fixture {
        val chat = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist-mark", ".json").absolutePath)
        )
    }

    @Test
    fun `reflector marks window lines as memorized after writing`() {
        val f = Fixture()
        f.chat.recordHistoryMessage(
            conversationId = "private:7", senderId = 7L, senderName = "甲",
            content = "我最喜欢的颜色是蓝色", timestamp = 1_000L, isFromBot = false
        )
        val memSvc = MemoryService(JsonFileMemoryRepository(File.createTempFile("mem-mark", ".json").absolutePath))
        val llm = FakeLLM(mutableListOf("""{"memories":[{"kind":"fact","content":"他最喜欢蓝色","tags":[]}]}"""))
        val reflector = MemoryReflector(
            memoryAgent = MemoryAgent(llm),
            memoryService = memSvc,
            chatHistoryService = f.chat,
            actionLog = ActionLog()
        )

        reflector.reflect("private:7")

        assertEquals(1, memSvc.getMemoriesOf(MemorySubject.user(7L)).size, "整理写入一条")
        assertTrue(
            f.chat.getHistorySummary("private:7", 10).contains("（已记忆）"),
            "写入成功后窗口行打标：${f.chat.getHistorySummary("private:7", 10)}"
        )
    }

    // ------------------------------------------------------------------
    // 退出整理判重视野：已有记忆要让 LLM 看得见（默认上限太小会漏判重）
    // ------------------------------------------------------------------

    @Test
    fun `summary default caps stay small but custom limits expose all`() {
        val s = service()
        repeat(7) { s.rememberFact(MemorySubject.user(9L), "事实$it") }

        val default = s.getMemorySummary(MemorySubject.user(9L))
        assertEquals(5, default.lines().count { it.startsWith("- ") }, "默认上限 5 条（控 prompt 体积）")

        val wide = s.getMemorySummary(MemorySubject.user(9L), factLimit = 50)
        assertEquals(7, wide.lines().count { it.startsWith("- ") }, "调大上限后全部可见")
    }

    @Test
    fun `reflector sees all existing memories for dedup`() {
        // 退出整理注入的现有记忆应含全部已记事实——LLM 看得见才不会重记
        val s = service()
        repeat(6) { s.rememberFact(MemorySubject.user(11L), "已有事实${'A' + it}") }

        val llm = FakeLLM(mutableListOf("""{"memories":[]}"""))
        val reflector = MemoryReflector(
            memoryAgent = MemoryAgent(llm),
            memoryService = s,
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist-reflect", ".json").absolutePath)
            ),
            actionLog = ActionLog()
        )
        reflector.reflect("private:11")

        val prompt = llm.prompts.single()
        repeat(6) { i ->
            val name = "已有事实${'A' + i}"
            assertTrue(prompt.contains(name), "第 $i 条已有记忆应出现在整理 prompt：$prompt")
        }
    }
}

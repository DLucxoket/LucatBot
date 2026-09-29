package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryExtractor
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** 记忆键空间隔离（群号与个人 QQ 号同号不互相污染） */
class MemorySubjectTest {

    @Test
    fun `same id different type does not leak`() {
        val service = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem-subject", ".json").absolutePath)
        )
        service.rememberFact(MemorySubject.user(123L), "个人的事实")
        service.rememberFact(MemorySubject.group(123L), "群聊的事实")

        assertEquals(1, service.getMemoriesOf(MemorySubject.user(123L)).size)
        assertEquals(1, service.getMemoriesOf(MemorySubject.group(123L)).size)

        val userSummary = service.getMemorySummary(MemorySubject.user(123L))
        assertTrue(userSummary.contains("个人的事实"), userSummary)
        assertFalse(userSummary.contains("群聊的事实"), "群记忆不应泄漏进个人摘要：$userSummary")

        val groupSummary = service.getMemorySummary(MemorySubject.group(123L))
        assertTrue(groupSummary.contains("群聊的事实"), groupSummary)
        assertFalse(groupSummary.contains("个人的事实"), groupSummary)
    }

    @Test
    fun `fromConversation derives subject type`() {
        assertEquals(MemorySubject.group(789L), MemorySubject.fromConversation("group:789"))
        assertEquals(MemorySubject.user(789L), MemorySubject.fromConversation("private:789"))
        // 解析失败按 USER 兜底，与历史行为兼容
        assertEquals(MemorySubject.user(0L), MemorySubject.fromConversation("broken"))
    }

    @Test
    fun `extract keeps subject type on produced memories`() {
        val items = MemoryExtractor.extract(MemorySubject.group(5L), "我喜欢猫")
        assertEquals(1, items.size)
        assertEquals(MemorySubject.Type.GROUP, items[0].subjectType)
        assertEquals(5L, items[0].userId)
    }

    @Test
    fun `extract blank message produces nothing`() {
        assertTrue(MemoryExtractor.extract(MemorySubject.user(1L), "").isEmpty())
        assertTrue(MemoryExtractor.extract(MemorySubject.user(1L), "   ").isEmpty())
    }
}

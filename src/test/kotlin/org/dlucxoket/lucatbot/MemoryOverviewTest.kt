package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 记忆注入的选择逻辑：
 * - 排序：最近写入优先（strength 恒 1.0，按它排 ≈ 按 UUID 随机挑——已改为 createdAt 倒序）
 * - 全量概览：不限当前会话——主体记忆 + 其他会话记忆（带主体标签、少量、最近优先）
 */
class MemoryOverviewTest {

    private val now = System.currentTimeMillis()
    private val day = 24 * 60 * 60 * 1000L

    private fun repo() = JsonFileMemoryRepository(File.createTempFile("mem-ov", ".json").absolutePath)

    @Test
    fun `memories are ordered newest first`() {
        val r = repo()
        val s = MemoryService(r)
        r.save(item("旧", now - 2 * day))
        r.save(item("新", now))
        r.save(item("中", now - day))

        assertEquals(listOf("新", "中", "旧"), s.getMemoriesOf(MemorySubject.user(1L)).map { it.content },
            "最近写入优先注入（不再是 strength/UUID 随机序）")
    }

    @Test
    fun `overview mixes primary block with labeled other subjects`() {
        val r = repo()
        val s = MemoryService(r)
        // 主体：user 1
        r.save(item("他在上海工作", now - 1000L).copy(userId = 1L))
        // 其他主体
        r.save(item("狐狐群的老朋友", now - 2000L).copy(userId = 777L))
        r.save(item("55群的周末约定", now - 3000L).copy(userId = 55L, subjectType = MemorySubject.Type.GROUP))

        val ov = s.getMemoryOverview(primary = MemorySubject.user(1L))
        assertTrue(ov.contains("关于此人的记忆："), "主体记忆块：$ov")
        assertTrue(ov.contains("他在上海工作"), ov)
        assertTrue(ov.contains("其他会话/对象的记忆"), "其他会话块：$ov")
        assertTrue(ov.contains("【QQ777】狐狐群的老朋友"), "跨会话记忆带主体标签：$ov")
        assertTrue(ov.contains("【群55】55群的周末约定"), "群记忆带标签：$ov")
        assertTrue(ov.contains("MemoryQueryTool"), "块内引导关键词搜索")
    }

    @Test
    fun `overview others are limited and sorted recent first`() {
        val r = repo()
        val s = MemoryService(r)
        // 4 条其他主体记忆，只取最近 3 条
        r.save(item("最老的背景", now - 4 * day).copy(userId = 2L))
        r.save(item("次老的背景", now - 3 * day).copy(userId = 3L))
        r.save(item("较新的背景", now - 2 * day).copy(userId = 4L))
        r.save(item("最新的背景", now - 1 * day).copy(userId = 5L))

        val ov = s.getMemoryOverview(primary = null, otherLimit = 3)
        val otherLines = ov.lines().filter { it.startsWith("- 【") }
        assertEquals(3, otherLines.size, "otherLimit 生效：$ov")
        assertFalse(ov.contains("最老的背景"), "最老的被截掉：$ov")
        assertTrue(ov.contains("最新的背景"), ov)
        assertTrue(
            ov.indexOf("最新的背景") < ov.indexOf("较新的背景"),
            "其他会话记忆按最近优先：$ov"
        )
        assertFalse(ov.contains("关于此人的记忆："), "primary=null 不渲染主体块")
    }

    @Test
    fun `overview empty when no memories at all`() {
        val s = MemoryService(repo())
        assertEquals("", s.getMemoryOverview(primary = MemorySubject.user(1L)))
    }

    private fun item(content: String, createdAt: Long) = MemoryItem(
        userId = 1L, subjectType = MemorySubject.Type.USER,
        type = MemoryItem.MemoryType.FACT, content = content, createdAt = createdAt
    )
}

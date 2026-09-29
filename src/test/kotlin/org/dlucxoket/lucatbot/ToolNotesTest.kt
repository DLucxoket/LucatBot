package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.tool.ToolNotes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * ToolNotes：目录去重、字符预算淘汰、连续步数护栏与状态软提示
 */
class ToolNotesTest {

    @Test
    fun `catalog is kept single replacing older one`() {
        val notes = ToolNotes()
        notes.recordCatalog(null, "### ATool\n功能：甲")
        notes.recordCatalog("关键词", "### BTool\n功能：乙")

        val snapshot = notes.snapshot()
        assertEquals(1, snapshot.count { it.startsWith("（工具目录") }, "同时只保留一份目录")
        assertTrue(snapshot[1].contains("搜索「关键词」"), snapshot[1])
        assertTrue(snapshot[1].contains("### BTool"), snapshot[1])
        assertFalse(snapshot.any { it.contains("### ATool") }, "旧目录应被覆盖")
    }

    @Test
    fun `status line counts and warns after three consecutive steps`() {
        val notes = ToolNotes()
        notes.recordCatalog(null, "目录")
        notes.recordResult("X", "{}")

        val status = notes.snapshot().first()
        assertTrue(status.contains("已查目录 1 次"), status)
        assertTrue(status.contains("已调用工具 1 次"), status)
        assertTrue(status.contains("连续工具步 2"), status)
        assertFalse(status.contains("请尽快给出正常决策"), "2 步不应点名：$status")

        notes.recordResult("X", "{}")  // 第 3 步
        assertTrue(notes.snapshot().first().contains("请尽快给出正常决策"), notes.snapshot().first())
    }

    @Test
    fun `reset streak zeroes consecutive counter but keeps totals`() {
        val notes = ToolNotes()
        notes.recordCatalog(null, "目录")
        notes.recordResult("X", "{}")
        notes.resetStreak()

        assertEquals(0, notes.steps)
        val status = notes.snapshot().first()
        assertTrue(status.contains("已查目录 1 次"), status)
        assertTrue(status.contains("已调用工具 1 次"), status)
        assertTrue(status.contains("连续工具步 0"), status)
    }

    @Test
    fun `exceeded at hard limit`() {
        val notes = ToolNotes(hardLimitSteps = 3)
        repeat(3) { notes.recordResult("X", "{}") }
        assertTrue(notes.exceeded(), "第 3 步应达上限")
    }

    @Test
    fun `budget evicts oldest results but keeps catalog`() {
        val notes = ToolNotes(maxTotalChars = 300)
        notes.recordCatalog(null, "C".repeat(100))          // 目录 ~120 字符
        notes.recordResult("T1", "R".repeat(100))
        notes.recordResult("T2", "R".repeat(100))
        notes.recordResult("T3", "R".repeat(100))           // 总额超 300 → 淘汰最旧结果

        val snapshot = notes.snapshot()
        assertTrue(snapshot.any { it.startsWith("（工具目录") }, "目录常驻")
        assertFalse(snapshot.any { it.contains("T1 执行结果") }, "最旧结果被淘汰")
        assertTrue(snapshot.any { it.contains("T3 执行结果") }, "最新结果保留")
        assertTrue(snapshot.sumOf { it.length } <= 300 + 200, "预算生效（状态行豁免）")
    }
}

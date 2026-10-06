package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.tool.ToolNotes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * ToolNotes：目录去重、字符预算淘汰、状态软提示与**连续错误护栏**
 *
 * 护栏语义（2026-10-01 调整）：**不限正常工具轮数**，只限连续调用错误——
 * 实盘曾被「连续步 ≥3」软提示在正常排查中途逼停，而真正该停的是反复报错空转。
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
    fun `status line counts steps but never pressures successful rounds`() {
        val notes = ToolNotes()
        notes.recordCatalog(null, "目录")
        notes.recordResult("X", "{}")

        val status = notes.snapshot().first()
        assertTrue(status.contains("已查目录 1 次"), status)
        assertTrue(status.contains("已调用工具 1 次"), status)
        assertTrue(status.contains("连续工具步 2"), status)
        assertTrue(status.contains("连续报错 0"), status)
        // 成功轮次不施加任何压力（旧版「≥3 请尽快给出正常决策」已移除）
        assertFalse(status.contains("请尽快给出正常决策"), "成功轮不点名：$status")
        assertFalse(status.contains("将强制停止"), "无错误不提限制：$status")

        // 连续 6 轮成功调用（旧版硬上限是 6/12）也不触发限制
        repeat(6) { notes.recordResult("X", "{}") }
        assertFalse(notes.errorsExceeded(), "多轮成功调用不触发限制")
    }

    @Test
    fun `status line warns on error and error limit is consecutive not cumulative`() {
        val notes = ToolNotes(maxConsecutiveErrors = 3)
        notes.recordResult("X", """{"success":false,"error":"炸了"}""", isError = true)

        val status = notes.snapshot().first()
        assertTrue(status.contains("连续报错 1"), status)
        assertTrue(status.contains("修正参数"), "报错时点名：$status")
        assertFalse(notes.errorsExceeded(), "1 次未达上限")

        // 成功一次即清零——「连续」语义而非累计
        notes.recordResult("Y", """{"success":true}""", isError = false)
        assertEquals(0, notes.consecutiveErrors, "成功清零错误连击")
        assertFalse(notes.errorsExceeded())

        // 连续 3 次失败 → 达上限
        repeat(3) { notes.recordResult("X", "{}", isError = true) }
        assertTrue(notes.errorsExceeded(), "连续 3 次失败达上限")
        assertEquals(3, notes.consecutiveErrors)
    }

    @Test
    fun `catalog steps do not count as errors`() {
        val notes = ToolNotes(maxConsecutiveErrors = 1)
        repeat(3) { notes.recordCatalog("q", "目录") }
        assertFalse(notes.errorsExceeded(), "list_tools 无失败态，不计错误")
        assertEquals(0, notes.consecutiveErrors)
    }

    @Test
    fun `reset streak zeroes consecutive counter but keeps totals`() {
        val notes = ToolNotes()
        notes.recordCatalog(null, "目录")
        notes.recordResult("X", "{}", isError = true)
        notes.resetStreak()

        assertEquals(0, notes.steps)
        assertEquals(0, notes.consecutiveErrors, "reset 连错误连击一起归零")
        val status = notes.snapshot().first()
        assertTrue(status.contains("已查目录 1 次"), status)
        assertTrue(status.contains("已调用工具 1 次"), status)
        assertTrue(status.contains("连续工具步 0"), status)
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
        assertTrue(snapshot.sumOf { it.length } <= 300 + 300, "预算生效（状态行豁免；状态行变长放宽一档）")
    }

    @Test
    fun `default budget keeps catalog plus about two results`() {
        // 生产默认预算 10_000（P1 从 16_000 收紧）：目录 ~3k 常驻 + 1~2 条结果。
        // 这段每轮注入 user 消息——多留 6k 字符 ≈ 每轮多烧 4k token，
        // 而第 3 条以后的结果重查一轮的成本并不更高
        val notes = ToolNotes()
        notes.recordCatalog(null, "C".repeat(3_000))
        repeat(3) { notes.recordResult("T$it", "R".repeat(3_000)) }

        val snapshot = notes.snapshot()
        assertEquals(1, snapshot.count { it.startsWith("（工具目录") }, "目录常驻且只一份")
        assertFalse(snapshot.any { it.contains("T0 执行结果") }, "最旧结果先被淘汰")
        assertEquals(2, snapshot.count { it.contains("执行结果") }, "预算内保留约两条结果：${snapshot.map { it.length }}")
        assertTrue(snapshot.sumOf { it.length } <= 10_000 + 300, "总预算 10_000 生效（状态行豁免）")
    }
}

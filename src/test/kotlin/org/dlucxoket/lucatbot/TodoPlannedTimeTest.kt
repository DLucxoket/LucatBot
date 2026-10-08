package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.data.TodoPlannedTime
import org.dlucxoket.lucatbot.bot.persona.TimeFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * `todo_add_time` 的宽松解析——**认不出只丢时间，绝不丢待办**
 *
 * LLM 拿到 prompt 的「当前时间」后按约定给 `yyyy-MM-dd HH:mm`，但实盘它
 * 偶尔少写年份、用 `T` 分隔、甚至只给 `HH:mm`。
 */
class TodoPlannedTimeTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(TimeFormat.ZONE).toInstant().toEpochMilli()

    @Test
    fun `full datetime parses to exact epoch`() {
        assertEquals(at(2026, 10, 7, 14, 30), TodoPlannedTime.parse("2026-10-07 14:30"))
        assertEquals(at(2026, 10, 7, 14, 30), TodoPlannedTime.parse("2026-10-07T14:30"), "T 分隔也认")
        assertEquals(at(2026, 10, 7, 14, 30), TodoPlannedTime.parse("  2026-10-07 14:30  "), "空白容忍")
        assertEquals(at(2026, 10, 7, 14, 30), TodoPlannedTime.parse("2026-10-07 14:30:00"), "带秒也认")
    }

    @Test
    fun `month day assumes current year`() {
        val year = LocalDate.now(TimeFormat.ZONE).year
        assertEquals(at(year, 11, 3, 9, 0), TodoPlannedTime.parse("11-03 09:00"))
    }

    @Test
    fun `time only means today or tomorrow if already past`() {
        val parsed = TodoPlannedTime.parse("09:15") ?: error("时分应能解析")
        val now = System.currentTimeMillis()
        assertTrue(parsed > now, "09:15 若已过点则顺延到明天，总之不会是过去时：$parsed vs $now")
    }

    @Test
    fun `unrecognized returns null instead of guessing`() {
        assertNull(TodoPlannedTime.parse("明天下午"))
        assertNull(TodoPlannedTime.parse("next tuesday"))
        assertNull(TodoPlannedTime.parse("2026/10/07 14:30"), "斜杠分隔不猜（宁可没计划也别错计划）")
        assertNull(TodoPlannedTime.parse(""))
        assertNull(TodoPlannedTime.parse(null))
    }
}

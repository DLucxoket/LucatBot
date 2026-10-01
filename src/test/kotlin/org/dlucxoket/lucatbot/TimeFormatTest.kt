package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.persona.TimeFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** TimeFormat：今日/跨天分流、短日期跨年规则、相对时长——渲染口径的回归锁 */
class TimeFormatTest {

    private val zone: ZoneId = TimeFormat.ZONE

    private fun atToday(hour: Int, minute: Int): Long =
        LocalDateTime.of(LocalDate.now(zone), java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    private fun daysAgo(days: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(LocalDate.now(zone).minusDays(days.toLong()), java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `today renders hour only in both line styles`() {
        val ts = atToday(14, 5)
        assertEquals("14:05", TimeFormat.chatLine(ts), "会话窗口今日只给时分")
        assertEquals("14:05", TimeFormat.listLine(ts), "列表行今日只给时分")
        assertEquals("14:05", TimeFormat.timeOnly(ts))
    }

    @Test
    fun `cross day styles diverge by design - chat full vs list compact`() {
        val ts = daysAgo(2, 9, 30)
        val expected = java.time.Instant.ofEpochMilli(ts).atZone(zone)
            .toLocalDateTime().toLocalDate().toString()
        assertEquals("$expected 09:30", TimeFormat.chatLine(ts),
            "会话窗口跨天带年月日（LLM 判断隔几天）")
        assertTrue(TimeFormat.listLine(ts).matches(Regex("""\d{2}-\d{2} 09:30""")),
            "列表行跨天是 MM-dd 紧凑格式：${TimeFormat.listLine(ts)}")
    }

    @Test
    fun `zero or negative timestamp renders empty`() {
        assertEquals("", TimeFormat.chatLine(0L))
        assertEquals("", TimeFormat.listLine(-5L))
    }

    @Test
    fun `monthDay same year is MM-dd cross year carries year`() {
        val thisYear = java.time.ZonedDateTime.of(
            LocalDate.of(LocalDate.now(zone).year, 6, 15), java.time.LocalTime.NOON, zone
        ).toInstant().toEpochMilli()
        assertEquals("06-15", TimeFormat.monthDay(thisYear), "同年只给月日")

        val lastYear = java.time.ZonedDateTime.of(
            LocalDate.of(LocalDate.now(zone).year - 1, 6, 15), java.time.LocalTime.NOON, zone
        ).toInstant().toEpochMilli()
        assertEquals("${LocalDate.now(zone).year - 1}-06-15", TimeFormat.monthDay(lastYear),
            "跨年带年份")
    }

    @Test
    fun `banDate delegates to monthDay`() {
        val lastYear = java.time.ZonedDateTime.of(
            LocalDate.of(LocalDate.now(zone).year - 1, 1, 2), java.time.LocalTime.NOON, zone
        ).toInstant().toEpochMilli()
        assertEquals(TimeFormat.monthDay(lastYear), TimeFormat.banDate(lastYear),
            "banDate 即 monthDay 的具名别名")
    }

    @Test
    fun `duration buckets`() {
        assertEquals("刚刚", TimeFormat.duration(59_000L))
        assertEquals("3 分钟前", TimeFormat.duration(3 * 60_000L))
        assertEquals("2 小时前", TimeFormat.duration(2 * 3_600_000L))
        assertEquals("1 天前", TimeFormat.duration(86_400_000L))
    }
}

package org.dlucxoket.lucatbot.bot.agent.data

import org.dlucxoket.lucatbot.bot.persona.TimeFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * `todo_add_time`（待办的**预计执行时间**）——**宽松解析**
 *
 * prompt 里带「当前时间」，LLM 按约定输出 `yyyy-MM-dd HH:mm`；但它偶尔会
 * 少写年份、用 `T` 分隔、甚至只给 `HH:mm`。规则：**尽量认，认不出就返回 null
 * （不带预计）——绝不因为时间格式把整条待办丢掉**（todo_add 内容照常入库）。
 *
 * 认得的形态：
 * - `yyyy-MM-dd HH:mm[:ss]`（完整）
 * - `MM-dd HH:mm`（默认当年）
 * - `HH:mm`（今天；**已过点 → 顺延到明天**，符合「晚上八点提醒我」的口语）
 */
object TodoPlannedTime {

    private val FULL_SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm")

    /** `MM-dd HH:mm` 形态（月/日/时可 1 位）——拼当年后按 [FULL] 解析 */
    private val MONTH_DAY_ONLY = Regex("""\d{1,2}-\d{1,2} \d{1,2}:\d{2}""")

    /**
     * 宽松解析成 epoch 毫秒（[TimeFormat.ZONE]）；认不出 → null
     *
     * @param raw LLM 给的时间串（可空、可带空白、可带 `T` 分隔）
     */
    fun parse(raw: String?): Long? {
        val text = raw?.trim()?.replace('T', ' ').orEmpty()
        if (text.isEmpty()) return null
        val zone = TimeFormat.ZONE
        val now = LocalDateTime.now(zone)

        parseDateTime(text, FULL_SECOND)?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        parseDateTime(text, FULL)?.let { return it.atZone(zone).toInstant().toEpochMilli() }

        // 只有月日 → 拼上当年再按完整格式解析（java.time 对缺年份的格式支持因版本而异，
        // 拼字符串最稳；跨年边缘交给「认不出就 null」，别瞎猜年份）
        if (MONTH_DAY_ONLY.matches(text)) {
            runCatching { LocalDateTime.parse("${now.year}-$text", FULL) }.getOrNull()
                ?.let { return it.atZone(zone).toInstant().toEpochMilli() }
        }

        // 只有时分 → 今天；已经过去就顺延明天
        parseLocalTime(text)?.let {
            var at = now.toLocalDate().atTime(it)
            if (at.isBefore(now)) at = at.plusDays(1)
            return at.atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    private fun parseDateTime(text: String, format: DateTimeFormatter): LocalDateTime? =
        runCatching { LocalDateTime.parse(text, format) }.getOrNull()

    private fun parseLocalTime(text: String): LocalTime? =
        runCatching { LocalTime.parse(text, TIME_ONLY) }.getOrNull()

    /** 当天是否已过（给 prompt 展示用不上，保留给调用方做「已过期」判断） */
    fun isPast(epochMs: Long): Boolean = epochMs < System.currentTimeMillis()

    /** 今天日期（供测试对齐口径） */
    fun today(): LocalDate = LocalDate.now(TimeFormat.ZONE)
}

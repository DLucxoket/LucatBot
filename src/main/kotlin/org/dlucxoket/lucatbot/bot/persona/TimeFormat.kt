package org.dlucxoket.lucatbot.bot.persona

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 会话时间格式化——**全项目展示时间的唯一渲染点**
 *
 * 此前 `formatTime` 有 4 份近似拷贝（ConversationController / MessageListController /
 * ActionLog / MemoryService），且两处编排层的非今日格式**已经分叉**
 * （`yyyy-MM-dd HH:mm` vs `MM-dd HH:mm`——同一时间在会话窗口与消息列表显示不同），
 * 时区字面量 `"Asia/Shanghai"` 另散落 10 处。收口到本对象后（DRY/SRP）：
 * - 改格式/时区只改这里；
 * - 两个编排层的分歧在此显式化为两个具名函数，调用方按语义选用。
 *
 * 时区固定北京时间——本项目「当前时间」语义即北京时间（LLM 判断消息新旧的依据）。
 */
object TimeFormat {

    /** 北京时间（全项目展示时间的唯一时区定义） */
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 今日消息：`HH:mm`；跨天可读格式（会话窗口用——带年月日，LLM 判断隔天/更早） */
    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 今日消息：`HH:mm`；跨天紧凑格式（消息列表行用——`MM-dd HH:mm`，行短省 token） */
    private val COMPACT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** 仅时分 */
    private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm")

    /** 只有日期（疑似禁言标记等）：同年 `MM-dd`，跨年 `yyyy-MM-dd` */
    private val BAN_DATE = DateTimeFormatter.ofPattern("MM-dd")

    /** 同上但跨年显式 */
    private val BAN_DATE_CROSS_YEAR = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 会话窗口的聊天记录行时间（[ConversationController] 用）：
     * 今日 → `HH:mm`；非今日 → `yyyy-MM-dd HH:mm`（LLM 要能看出隔了几天）
     */
    fun chatLine(ts: Long): String = render(ts, FULL)

    /**
     * 消息列表行时间（[MessageListController] 用）：
     * 今日 → `HH:mm`；非今日 → `MM-dd HH:mm`（列表行紧凑，不显示年份）
     */
    fun listLine(ts: Long): String = render(ts, COMPACT)

    /** 时分（`HH:mm`；操作日志等只要时刻的场合） */
    fun timeOnly(ts: Long): String = render(ts, TIME_ONLY)

    /** 当日日期（`yyyy-MM-dd`；固定短记、记忆戳等） */
    fun date(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /**
     * 短日期：同年 `MM-dd`，跨年 `yyyy-MM-dd`（跨年带年份免得只剩 01-02 分不清年）。
     *
     * 通用语义（疑似禁言标记、记忆「（记于…）」戳等同款格式共用本函数）。
     */
    fun monthDay(epochMs: Long): String {
        val date = Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate()
        val pattern = if (date.year == LocalDate.now(ZONE).year) BAN_DATE else BAN_DATE_CROSS_YEAR
        return date.format(pattern)
    }

    /**
     * 疑似禁言标记日期（消息列表【疑似禁言 MM-dd】）——即 [monthDay]，
     * 具名别名保留调用点语义可读。
     */
    fun banDate(epochMs: Long): String = monthDay(epochMs)

    /**
     * 相对时长（「我上次发言 X 前」）：刚刚 / X 分钟前 / X 小时前 / X 天前
     */
    fun duration(ms: Long): String = when {
        ms < 60_000L -> "刚刚"
        ms < 3_600_000L -> "${ms / 60_000L} 分钟前"
        ms < 86_400_000L -> "${ms / 3_600_000L} 小时前"
        else -> "${ms / 86_400_000L} 天前"
    }

    /** 今日 vs 非今日分流渲染；`ts <= 0` 返回空串（旧数据无时间戳的兜底） */
    private fun render(ts: Long, nonToday: DateTimeFormatter): String {
        if (ts <= 0L) return ""
        val zoned = Instant.ofEpochMilli(ts).atZone(ZONE)
        val pattern = if (zoned.toLocalDate() == LocalDate.now(ZONE)) TIME_ONLY else nonToday
        return zoned.format(pattern)
    }
}

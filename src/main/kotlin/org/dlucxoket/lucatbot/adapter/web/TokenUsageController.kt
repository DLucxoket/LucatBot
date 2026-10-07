package org.dlucxoket.lucatbot.adapter.web

import org.dlucxoket.lucatbot.bot.llm.TokenUsageAggregator
import org.dlucxoket.lucatbot.bot.llm.TokenUsageChannel
import org.dlucxoket.lucatbot.bot.llm.TokenUsageEntry
import org.dlucxoket.lucatbot.bot.persona.TimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * token 用量统计 API——`web/` Vue 面板的数据源
 *
 * 三个端点对应三种看数据的方式：
 * - `/summary`：**当前累计**（按 通道/agent/会话/模型 四个维度分组，前端画环图与条形图）
 * - `/timeseries`：**时间轴**（按天/按小时分桶，前端画趋势曲线）
 * - `/calls`：**明细**（最近 N 次调用，前端画表格）
 *
 * 聚合器**构造注入**（DIP）：单测传 `TokenUsageAggregator(替身存储)` 即可，
 * 不改任何全局状态；本类只做分组/分桶的读侧加工，不含业务规则（SRP）。
 */
@RestController
@RequestMapping("/api/token")
class TokenUsageController(
    private val tokenUsage: TokenUsageAggregator
) {

    /** 汇总行（前端直接喂图表） */
    data class TokenRowDto(
        val name: String,
        val calls: Long,
        val input: Long,
        val output: Long,
        val total: Long
    )

    data class TokenSummaryDto(
        val total: TokenRowDto,
        /** 端点没回 usage 的调用数（token 按 0 计，如实展示） */
        val noUsageCalls: Long,
        val byChannel: List<TokenRowDto>,
        val byAgent: List<TokenRowDto>,
        val byConversation: List<TokenRowDto>,
        val byModel: List<TokenRowDto>
    )

    /** 时间分桶行；[bucket] 为可排序的 `yyyy-MM-dd` 或 `yyyy-MM-dd HH` */
    data class TokenBucketDto(
        val bucket: String,
        val calls: Long,
        val input: Long,
        val output: Long,
        val total: Long
    )

    @GetMapping("/summary")
    fun summary(): TokenSummaryDto {
        val entries = tokenUsage.snapshot()
        return TokenSummaryDto(
            total = TokenRowDto(
                name = "合计",
                calls = entries.sumOf { it.calls },
                input = entries.sumOf { it.input },
                output = entries.sumOf { it.output },
                total = entries.sumOf { it.total }
            ),
            noUsageCalls = tokenUsage.noUsageCalls(),
            // 通道按固定顺序（文字 → 图片），零值也展示——一眼看出「图片通道用没用」
            byChannel = TokenUsageChannel.entries.map { ch ->
                sumRow(ch.label, entries.filter { it.channel == ch.label })
            },
            byAgent = grouped(entries) { it.agent },
            byConversation = grouped(entries) { it.conversation },
            byModel = grouped(entries) { it.model }
        )
    }

    /**
     * 时间轴分桶
     *
     * @param bucket `day`（默认，按天）或 `hour`（按小时）
     * @param limit  只返回最近 N 个桶（<=0 = 全部）
     */
    @GetMapping("/timeseries")
    fun timeseries(
        @RequestParam(defaultValue = "day") bucket: String,
        @RequestParam(defaultValue = "30") limit: Int
    ): List<TokenBucketDto> {
        val hour = bucket.equals("hour", ignoreCase = true)
        val fmt = DateTimeFormatter.ofPattern(if (hour) "yyyy-MM-dd HH" else "yyyy-MM-dd")
        return tokenUsage.history(limit = 50_000)
            .groupBy { Instant.ofEpochMilli(it.epoch).atZone(TimeFormat.ZONE).format(fmt) }
            .map { (key, calls) ->
                TokenBucketDto(
                    bucket = key,
                    calls = calls.size.toLong(),
                    input = calls.sumOf { it.input },
                    output = calls.sumOf { it.output },
                    total = calls.sumOf { it.total }
                )
            }
            .sortedBy { it.bucket }
            .let { if (limit > 0) it.takeLast(limit) else it }
    }

    /** 调用明细（时间倒序；前端表格用） */
    @GetMapping("/calls")
    fun calls(@RequestParam(defaultValue = "200") limit: Int) =
        tokenUsage.history(limit = limit.coerceIn(1, 2_000))

    /** 按选择器分组求和，合计降序 */
    private fun grouped(
        entries: List<TokenUsageEntry>,
        keyOf: (TokenUsageEntry) -> String
    ): List<TokenRowDto> = entries.groupBy(keyOf)
        .map { (name, list) -> sumRow(name, list) }
        .sortedByDescending { it.total }

    /** 一组求和成一行 */
    private fun sumRow(name: String, list: List<TokenUsageEntry>) = TokenRowDto(
        name = name,
        calls = list.sumOf { it.calls },
        input = list.sumOf { it.input },
        output = list.sumOf { it.output },
        total = list.sumOf { it.total }
    )
}

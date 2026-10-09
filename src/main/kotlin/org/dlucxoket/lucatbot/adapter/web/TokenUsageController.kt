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
 * 四个端点对应四种看数据的方式：
 * - `/summary`：**当前累计**（按 通道/agent/会话/模型 四个维度分组，前端画环图与条形图）
 * - `/timeseries`：**时间轴**（按天/按小时分桶，前端画趋势曲线）
 * - `/calls`：**明细**（最近 N 次调用，前端轨迹左栏列表——不带请求/响应正文）
 * - `/call?id=`：**单次详情**（发给 LLM 的真实请求、模型回复、耗时、异常）
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
        /** 进场首轮的调用次数（P5：验证「进场预搜 + 首轮可直接回话」省了几轮） */
        val firstRoundCalls: Long,
        val byChannel: List<TokenRowDto>,
        val byAgent: List<TokenRowDto>,
        val byConversation: List<TokenRowDto>,
        val byModel: List<TokenRowDto>,
        /** 按这次调用产出的决策动作分组（send / idle / read_more / 拆词…） */
        val byAction: List<TokenRowDto>
    )

    /** 时间分桶行；[bucket] 为可排序的 `yyyy-MM-dd` 或 `yyyy-MM-dd HH` */
    data class TokenBucketDto(
        val bucket: String,
        val calls: Long,
        val input: Long,
        val output: Long,
        val total: Long
    )

    /**
     * 轨迹明细行（列表接口）——**不带 request/response 正文**（一次调用的请求
     * 动辄十几 KB，列表带全会撑爆响应）；正文与完整错误走 `/call?id=` 详情。
     */
    data class TokenCallRowDto(
        val id: Long?,
        val epoch: Long,
        val agent: String,
        val conversation: String,
        val channel: String,
        val model: String,
        val input: Long,
        val output: Long,
        val reported: Boolean,
        val action: String,
        val firstRound: Boolean,
        val durationMs: Long?,
        val error: String?
    ) {
        val total: Long get() = input + output
    }

    /** 单次调用**详情**（含真实请求 / 模型回复 / 耗时 / 异常）——轨迹面板点开用 */
    data class TokenCallDetailDto(
        val id: Long?,
        val epoch: Long,
        val agent: String,
        val conversation: String,
        val channel: String,
        val model: String,
        val input: Long,
        val output: Long,
        val reported: Boolean,
        val action: String,
        val firstRound: Boolean,
        val durationMs: Long?,
        val request: String?,
        val response: String?,
        val error: String?
    ) {
        val total: Long get() = input + output
    }

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
            firstRoundCalls = tokenUsage.firstRoundCalls(),
            // 通道按固定顺序（文字 → 图片），零值也展示——一眼看出「图片通道用没用」
            byChannel = TokenUsageChannel.entries.map { ch ->
                sumRow(ch.label, entries.filter { it.channel == ch.label })
            },
            byAgent = grouped(entries) { it.agent },
            byConversation = grouped(entries) { it.conversation },
            byModel = grouped(entries) { it.model },
            byAction = grouped(entries) { it.action }
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

    /** 调用明细（时间倒序；轨迹左栏列表用——不含请求/响应正文） */
    @GetMapping("/calls")
    fun calls(@RequestParam(defaultValue = "200") limit: Int): List<TokenCallRowDto> =
        tokenUsage.history(limit = limit.coerceIn(1, 2_000)).map { row ->
            TokenCallRowDto(
                id = row.id, epoch = row.epoch, agent = row.agent, conversation = row.conversation,
                channel = row.channel, model = row.model, input = row.input, output = row.output,
                reported = row.reported, action = row.action, firstRound = row.firstRound,
                durationMs = row.durationMs, error = row.error
            )
        }

    /**
     * 单次调用详情——**发给 LLM 的真实请求**（system/user 正文）与**模型回复**、
     * 耗时、端点异常都在这里；查不到 → 404
     */
    @GetMapping("/call")
    fun call(@RequestParam id: Long): TokenCallDetailDto {
        val row = tokenUsage.findCall(id)
            ?: throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "调用不存在：id=$id"
            )
        return TokenCallDetailDto(
            id = row.id, epoch = row.epoch, agent = row.agent, conversation = row.conversation,
            channel = row.channel, model = row.model, input = row.input, output = row.output,
            reported = row.reported, action = row.action, firstRound = row.firstRound,
            durationMs = row.durationMs, request = row.request, response = row.response,
            error = row.error
        )
    }

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

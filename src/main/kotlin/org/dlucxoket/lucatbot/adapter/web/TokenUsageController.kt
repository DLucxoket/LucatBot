package org.dlucxoket.lucatbot.adapter.web

import org.dlucxoket.lucatbot.bot.llm.PricingTable
import org.dlucxoket.lucatbot.bot.llm.TokenUsageAggregator
import org.dlucxoket.lucatbot.bot.llm.TokenUsageCall
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
    private val tokenUsage: TokenUsageAggregator,
    /** 当前平台计费表（按模型三档计费；没配 = 空表 = 不显示、不猜价） */
    private val pricing: PricingTable = PricingTable()
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
        /** prompt 缓存命中的输入 token 累计（入参子集；端点不回 = 0，如实显示） */
        val cachedInput: Long,
        /** 估算消耗（读侧按计费表现算：按模型三档，命中缓存走缓存档）——没配价 = null */
        val estimatedCost: Double?,
        /** 计费单位（"Credits" / "元"…；没配价 = null） */
        val costUnit: String?,
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
        val error: String?,
        /** prompt 缓存命中（入参子集；端点不回 = null） */
        val cached: Long?,
        /** 本次消耗（按模型三档现算；该模型没配价 = null） */
        val cost: Double?
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
        val error: String?,
        val cached: Long?,
        /** 本次消耗（按模型三档现算：未命中输入/命中缓存/输出；没配价 = null） */
        val cost: Double?,
        /** 计费单位（没配价 = null） */
        val costUnit: String?
    ) {
        val total: Long get() = input + output
    }

    @GetMapping("/summary")
    fun summary(): TokenSummaryDto {
        val entries = tokenUsage.snapshot()
        val totalInput = entries.sumOf { it.input }
        val totalOutput = entries.sumOf { it.output }
        val cached = tokenUsage.cachedTokens()
        return TokenSummaryDto(
            total = TokenRowDto(
                name = "合计",
                calls = entries.sumOf { it.calls },
                input = totalInput,
                output = totalOutput,
                total = totalInput + totalOutput
            ),
            noUsageCalls = tokenUsage.noUsageCalls(),
            firstRoundCalls = tokenUsage.firstRoundCalls(),
            cachedInput = cached,
            estimatedCost = entries.mapNotNull {
                pricing.estimate(it.model, it.input, it.output, it.cached)
            }.takeIf { it.isNotEmpty() }?.sum(),
            costUnit = pricing.unit.ifBlank { null },
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
     * 时间轴分桶（**单线**：整段时间的总量趋势）
     *
     * @param bucket `day`（默认，按天）或 `hour`（按小时）
     * @param limit  只返回最近 N 个桶（<=0 = 全部）
     */
    @GetMapping("/timeseries")
    fun timeseries(
        @RequestParam(defaultValue = "day") bucket: String,
        @RequestParam(defaultValue = "30") limit: Int
    ): List<TokenBucketDto> {
        val fmt = bucketFormatter(bucket)
        return tokenUsage.history(limit = 50_000)
            .groupBy { bucketOf(it.epoch, fmt) }
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

    /** 时间轴分桶 × 分类键的一行（趋势图拆成多条线用） */
    data class TokenSeriesRowDto(
        val bucket: String,
        /** 分类键（动作 / 会话 / agent / 模型 / 通道） */
        val key: String,
        val calls: Long,
        val input: Long,
        val output: Long,
        val total: Long
    )

    /**
     * 时间轴分桶 × 分类（趋势图**分线**）
     *
     * @param bucket  `day`（按天）或 `hour`（按小时）
     * @param limit   只保留最近 N 个时间桶（<=0 = 全部）
     * @param groupBy 分类维度：`action` / `conversation` / `agent` / `model` / `channel`；
     *                其他值 → 400（前端白名单写死这五个）
     * @return 扁平行 `[bucket, key, calls, input, output, total]`——前端按 key 拆线、
     *         按 bucket 对齐（缺桶补 0），一次请求拿全，不必逐 key 打接口
     */
    @GetMapping("/timeseries-grouped")
    fun timeseriesGrouped(
        @RequestParam(defaultValue = "day") bucket: String,
        @RequestParam(defaultValue = "30") limit: Int,
        @RequestParam(defaultValue = "action") groupBy: String
    ): List<TokenSeriesRowDto> {
        val fmt = bucketFormatter(bucket)
        val allowed = setOf("action", "conversation", "agent", "model", "channel")
        if (groupBy !in allowed) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "不支持的分组维度：$groupBy（可选：${allowed.joinToString("/")}）"
            )
        }
        // when 放进 lambda：expected type 不会穿进 when 分支，写成分支级 lambda 会解析不到 it
        val keyOf: (TokenUsageCall) -> String = { c ->
            when (groupBy) {
                "action" -> c.action
                "conversation" -> c.conversation
                "agent" -> c.agent
                "model" -> c.model
                else -> c.channel
            }
        }
        val rows = tokenUsage.history(limit = 50_000)
            .groupBy { bucketOf(it.epoch, fmt) to keyOf(it) }
            .map { (bk, calls) ->
                TokenSeriesRowDto(
                    bucket = bk.first,
                    key = bk.second,
                    calls = calls.size.toLong(),
                    input = calls.sumOf { it.input },
                    output = calls.sumOf { it.output },
                    total = calls.sumOf { it.total }
                )
            }
        // 只保留最近 N 个时间桶（跨桶统一裁剪，各 key 一致）
        val keep = rows.map { it.bucket }.distinct().sorted()
            .let { if (limit > 0) it.takeLast(limit) else it }
            .toSet()
        return rows.filter { it.bucket in keep }
            .sortedWith(compareBy({ it.bucket }, { -it.total }))
    }

    /** 时间桶格式（`yyyy-MM-dd` 或 `yyyy-MM-dd HH`）——两个趋势端点共用 */
    private fun bucketFormatter(bucket: String): DateTimeFormatter {
        val hour = bucket.equals("hour", ignoreCase = true)
        return DateTimeFormatter.ofPattern(if (hour) "yyyy-MM-dd HH" else "yyyy-MM-dd")
    }

    /** epoch → 时间桶（[TimeFormat.ZONE]，与明细面板同一口径） */
    private fun bucketOf(epoch: Long, fmt: DateTimeFormatter): String =
        Instant.ofEpochMilli(epoch).atZone(TimeFormat.ZONE).format(fmt)

    /** 调用明细（时间倒序；轨迹左栏列表用——不含请求/响应正文） */
    @GetMapping("/calls")
    fun calls(@RequestParam(defaultValue = "200") limit: Int): List<TokenCallRowDto> =
        tokenUsage.history(limit = limit.coerceIn(1, 2_000)).map { row ->
            TokenCallRowDto(
                id = row.id, epoch = row.epoch, agent = row.agent, conversation = row.conversation,
                channel = row.channel, model = row.model, input = row.input, output = row.output,
                reported = row.reported, action = row.action, firstRound = row.firstRound,
                durationMs = row.durationMs, error = row.error, cached = row.cached,
                cost = pricing.estimate(row.model, row.input, row.output, row.cached)
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
            error = row.error, cached = row.cached,
            cost = pricing.estimate(row.model, row.input, row.output, row.cached),
            costUnit = pricing.unit.ifBlank { null }
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

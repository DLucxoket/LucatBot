package org.dlucxoket.lucatbot.bot.llm

import org.slf4j.LoggerFactory

/**
 * token 用量**聚合器**——按维度分门别类统计（**构造注入存储，不是全局单例**）
 *
 * 三个维度（键 = agent × 会话 × 通道，另带模型）：
 * - **通道**：[TokenUsageChannel.TEXT] 文字 / [TokenUsageChannel.IMAGE] 图片
 * - **agent**：ConversationAgent / SessionPickerAgent / MemoryAgent / NotificationAgent
 * - **会话**：会话键（`private:123` / `group:456`）、`消息列表`、`通知:…`
 *
 * 职责边界（SRP）：
 * - **本类**：内存聚合 + 分维度汇总渲染 + 周期性日志
 * - **[TokenUsageStore]**：明细读写（jsonl / 内存）
 * - **[TokenUsageScope]**：维度从哪来（ThreadLocal 环境态）
 * - **[OpenAIAPI]**：什么时候记（拿到 tokenUsage 的唯一时刻）
 *
 * 首次访问会把存储里的历史读回内存 → 汇总**跨重启累计**（明细不进内存聚合
 * 的代价是 history 每次读存储，读侧请求频率低，可接受）。
 *
 * 依赖装配见 [TokenUsageConfig]（Spring）；单测直接 `new` 替身存储即可，
 * **不需要改任何全局状态**。
 */
class TokenUsageAggregator(
    private val store: TokenUsageStore,
    /** 每累计多少次调用打一次分维度汇总（<=0 = 不自动打） */
    private val summaryEvery: Int = 10
) {
    private val log = LoggerFactory.getLogger(TokenUsageAggregator::class.java)

    /** 统计键：agent × 会话 × 通道 × 模型 × 动作 × 是否进场首轮（换端点/换模型后成本可比） */
    private data class Key(
        val agent: String,
        val conversation: String,
        val channel: TokenUsageChannel,
        val model: String,
        val action: String,
        val firstRound: Boolean
    )

    /** 一个键上的累计值（calls / 入参 token / 出参 token；不可变 + plus 累加） */
    private data class Agg(val calls: Long, val input: Long, val output: Long) {
        val total: Long get() = input + output
        operator fun plus(other: Agg) =
            Agg(calls + other.calls, input + other.input, output + other.output)
    }

    private val lock = Any()
    private val totals = LinkedHashMap<Key, Agg>()

    /** 端点没回 usage 的调用次数（token 数按 0 计，如实单列，不混进正常值） */
    private var noUsage = 0L
    private var loaded = false
    private var sinceSummary = 0

    /**
     * 记一次 LLM 调用（由 [org.dlucxoket.lucatbot.bot.agent.Agent] 在 handle 结束时调用）
     *
     * @param input 入参 token（含图片请求的视觉 token）；端点未返回记 0 并计一次 noUsage
     * @param output 出参 token
     * @param action 这次调用产出的决策动作（send / idle / read_more…；没打上 = "-"）
     * @param firstRound 是否本会话首轮（进场首轮占比统计）
     * @param durationMs 调用耗时（毫秒）
     * @param request 发给 LLM 的真实请求正文（轨迹；列表接口不返回，按 id 详情取）
     * @param response 模型回复正文
     * @param error 端点异常（成功 = null）
     */
    fun record(
        agent: String,
        conversation: String,
        channel: TokenUsageChannel,
        model: String,
        input: Long?,
        output: Long?,
        action: String = "-",
        firstRound: Boolean = false,
        durationMs: Long? = null,
        request: String? = null,
        response: String? = null,
        error: String? = null
    ) {
        val inTok = input ?: 0L
        val outTok = output ?: 0L
        val reported = input != null || output != null
        val key = Key(agent, conversation, channel, model, action, firstRound)
        synchronized(lock) {
            ensureLoadedLocked()
            totals[key] = (totals[key] ?: Agg(0, 0, 0)) + Agg(1, inTok, outTok)
            if (!reported) noUsage++
            store.append(
                TokenUsageCall(
                    epoch = System.currentTimeMillis(),
                    agent = agent,
                    conversation = conversation,
                    channel = channel.label,
                    model = model,
                    input = inTok,
                    output = outTok,
                    reported = reported,
                    action = action,
                    firstRound = firstRound,
                    durationMs = durationMs,
                    request = request,
                    response = response,
                    error = error
                )
            )
            sinceSummary++
            if (summaryEvery > 0 && sinceSummary >= summaryEvery) {
                sinceSummary = 0
                log.info("\n{}", summaryLocked())
            }
        }
    }

    /** 分维度汇总文本（跨重启累计）——按通道 / 按 agent / 按会话（前 N 个） / 按模型 */
    fun summary(topConversations: Int = 8): String = synchronized(lock) {
        ensureLoadedLocked()
        summaryLocked(topConversations)
    }

    /** 端点没回 usage 的调用数（跨重启累计，前端如实标注「按 0 计」） */
    fun noUsageCalls(): Long = synchronized(lock) {
        ensureLoadedLocked()
        noUsage
    }

    /** 进场首轮的调用次数（跨重启累计）——面板算「进场首轮占比」用 */
    fun firstRoundCalls(): Long = synchronized(lock) {
        ensureLoadedLocked()
        aggregate { it.firstRound }.calls
    }

    /**
     * 结构化快照（**Web API 用**）：按统计键分组的累计值，合计降序
     *
     * 与 [summary] 同一份数据，只是不渲染成文本——前端自己决定怎么透视
     */
    fun snapshot(): List<TokenUsageEntry> = synchronized(lock) {
        ensureLoadedLocked()
        totals.map { (k, v) ->
            TokenUsageEntry(
                agent = k.agent,
                conversation = k.conversation,
                channel = k.channel.label,
                model = k.model,
                action = k.action,
                firstRound = k.firstRound,
                calls = v.calls,
                input = v.input,
                output = v.output
            )
        }.sortedByDescending { it.total }
    }

    /**
     * 调用明细（**时间轴/明细表用**）——时间倒序，**不带大字段**（请求/响应正文）
     *
     * 聚合在内存、明细在存储：内存不随调用次数膨胀，历史全量可查；
     * 正文走 [findCall] 按 id 单取。
     */
    fun history(limit: Int = 500): List<TokenUsageCall> =
        store.readRecent(limit)

    /**
     * 单次调用**详情**（含发给 LLM 的真实请求、模型回复、耗时与异常）——
     * 轨迹面板按 id 点开时取；查不到 = null
     */
    fun findCall(id: Long?): TokenUsageCall? = store.find(id)

    // ------------------------------------------------------------------
    // 内部实现（一律在 [lock] 内调用）
    // ------------------------------------------------------------------

    /** 首次访问把存储里的历史读回内存——汇总跨重启累计 */
    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        store.readAll().forEach { row ->
            val key = Key(
                agent = row.agent,
                conversation = row.conversation,
                channel = TokenUsageChannel.fromLabel(row.channel),
                model = row.model,
                action = row.action,
                firstRound = row.firstRound
            )
            // 明细一行 = 一次调用（calls 恒为 1，模型里不再带该字段）
            totals[key] = (totals[key] ?: Agg(0, 0, 0)) + Agg(1, row.input, row.output)
            if (!row.reported) noUsage++
        }
    }

    private fun summaryLocked(topConversations: Int = 8): String {
        if (totals.isEmpty()) return "token 用量：暂无记录"
        val grand = totals.values.reduce { a, b -> a + b }
        val sb = StringBuilder()
        sb.append("token 用量汇总（累计 ${grand.calls} 次：入 ${grand.input} / 出 ${grand.output} / 合计 ${grand.total}）")
        if (noUsage > 0) sb.append("；另有 $noUsage 次端点未返回用量（按 0 计）")

        sb.append("\n- 按通道：").append(
            render(
                TokenUsageChannel.entries.map { ch ->
                    ch.label to aggregate { k -> k.channel == ch }
                }
            )
        )
        sb.append("\n- 按 agent：").append(
            render(
                totals.keys.map { it.agent }.distinct().sorted()
                    .map { a -> a to aggregate { k -> k.agent == a } }
            )
        )
        val convs = totals.keys.map { it.conversation }.distinct()
            .map { c -> c to aggregate { k -> k.conversation == c } }
            .sortedByDescending { it.second.total }
        val head = convs.take(topConversations)
        val rest = convs.drop(topConversations)
        val convText = render(head) + if (rest.isEmpty()) {
            ""
        } else {
            val restAgg = rest.map { it.second }.reduce { a, b -> a + b }
            "｜（其他 ${rest.size} 个会话 ${fmt(restAgg)}）"
        }
        sb.append("\n- 按会话：").append(convText)
        sb.append("\n- 按模型：").append(
            render(
                totals.keys.map { it.model }.distinct().sorted()
                    .map { m -> m to aggregate { k -> k.model == m } }
            )
        )
        sb.append("\n- 按动作：").append(
            render(
                totals.keys.map { it.action }.distinct().sorted()
                    .map { a -> a to aggregate { k -> k.action == a } }
            )
        )
        val firstRounds = aggregate { k -> k.firstRound }
        if (firstRounds.calls > 0) {
            sb.append("\n- 进场首轮：${firstRounds.calls} 次（入 ${firstRounds.input} / 出 ${firstRounds.output}）")
        }
        return sb.toString()
    }

    /** 按谓词聚合成一行「名称 次数 入/出/合」 */
    private fun render(rows: List<Pair<String, Agg>>): String =
        rows.joinToString("｜") { (name, agg) -> "$name ${fmt(agg)}" }

    private fun fmt(agg: Agg): String = "${agg.calls}次 入${agg.input}/出${agg.output}/合${agg.total}"

    private fun aggregate(predicate: (Key) -> Boolean): Agg =
        totals.filterKeys(predicate).values.reduceOrNull { a, b -> a + b } ?: Agg(0, 0, 0)
}

/**
 * 各 Agent 的**默认账本**：不落盘的内存实现（单测零配置、不污染 `data/`）
 *
 * 生产由 `LoopConfig` 注入容器里的**同一个** bean——不注入也能跑，但各 Agent
 * 会各记各的，面板上的分组就碎了（装配守卫测试专门盯这一点）。
 */
fun inMemoryTokenUsage(): TokenUsageAggregator =
    TokenUsageAggregator(InMemoryTokenUsageStore(), summaryEvery = 0)

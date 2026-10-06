package org.dlucxoket.lucatbot.bot.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.File

/**
 * token 用量的**维度上下文**（ThreadLocal）
 *
 * 为什么要独立一层：`OpenAIAPI.ask()` 只看得到 prompt 与响应，不知道「是谁在问、
 * 问的是哪个会话」——而统计要的正是这两个维度。调用方（Agent / 编排层）在
 * 调 LLM 之前把维度挂到当前线程，`OpenAIAPI` 拿到 tokenUsage 时顺手取走，
 * LLM 接口本身不加参数（ISP：`LLM` 只暴露 `ask()`）。
 *
 * 线程内可嵌套（`try/finally` 还原上一层），执行完即还原，不污染同线程后续调用。
 *
 * 两个维度：
 * - **agent**：由 [org.dlucxoket.lucatbot.bot.agent.Agent] 的入口统一设置
 *   （默认类名，如 `ConversationAgent`）
 * - **conversation**：由编排层在调 Agent 前设置（会话键 `private:123` /
 *   `group:456`、`消息列表`、`通知:private:123`）
 */
object TokenUsageScope {
    private val agentTag = ThreadLocal.withInitial { "unknown" }
    private val conversationTag = ThreadLocal.withInitial { "-" }

    /** 当前线程的「agent」维度（默认 unknown = 没经过 Agent 入口） */
    fun agent(): String = agentTag.get()

    /** 当前线程的「会话」维度（默认 "-" = 没经编排层设置） */
    fun conversation(): String = conversationTag.get()

    /** 在 [block] 期间把 agent 维度设为 [name]（执行完还原上一层） */
    fun <T> withAgent(name: String, block: () -> T): T {
        val prev = agentTag.get()
        agentTag.set(name)
        return try {
            block()
        } finally {
            agentTag.set(prev)
        }
    }

    /** 在 [block] 期间把会话维度设为 [conversation]（执行完还原上一层） */
    fun <T> withConversation(conversation: String, block: () -> T): T {
        val prev = conversationTag.get()
        conversationTag.set(conversation)
        return try {
            block()
        } finally {
            conversationTag.set(prev)
        }
    }
}

/**
 * token 用量账本——**按维度分门别类统计**并落盘
 *
 * 三个维度（键 = agent × 会话 × 通道）：
 * - **通道**：[Channel.TEXT] 文字 / [Channel.IMAGE] 图片（带图请求的视觉 token
 *   计在 input 里，靠通道区分出来）
 * - **agent**：ConversationAgent / SessionPickerAgent / MemoryAgent / NotificationAgent
 * - **会话**：会话键（`private:123` / `group:456`）、`消息列表`、`通知:…`
 *
 * 数据两处：
 * - **累计**：`data/token-usage.jsonl` 每次调用追加一行（append-only，崩溃不丢），
 *   首次使用时整表读回 → 汇总**跨重启累计**；离线想怎么透视都行
 * - **实时**：每 [summaryEvery] 次调用打一条 INFO 分维度汇总，停机再打一条（终值）
 *
 * 统计是**观测**不是业务数据，故走 jsonl 文件、不进 `storage.type` 后端。
 */
object TokenUsageLedger {

    /** 通道维度：文字 / 图片 */
    enum class Channel(val label: String) {
        TEXT("文字"), IMAGE("图片")
    }

    /** 统计键：agent × 会话 × 通道 */
    private data class Key(val agent: String, val conversation: String, val channel: Channel)

    /** 一个键上的累计值（calls / 入参 token / 出参 token） */
    private data class Agg(val calls: Long, val input: Long, val output: Long) {
        val total: Long get() = input + output
        operator fun plus(other: Agg) =
            Agg(calls + other.calls, input + other.input, output + other.output)
    }

    private val log = LoggerFactory.getLogger(TokenUsageLedger::class.java)
    private val mapper = jacksonObjectMapper()
    private val lock = Any()
    private val totals = LinkedHashMap<Key, Agg>()

    /** 端点没回 usage 的调用次数（token 数按 0 计，如实单列，不混进正常值） */
    private var noUsageCalls = 0L
    private var loaded = false
    private var filePath: String? = "data/token-usage.jsonl"
    private var sinceSummary = 0

    /** 每累计多少次调用打一次分维度汇总（<=0 = 不自动打；测试关掉） */
    @Volatile
    var summaryEvery: Int = 10

    /**
     * 配置落盘路径（**null = 只统计不落盘**）——测试用临时文件或 null，
     * 避免污染 `data/`；下次访问按新路径重新加载
     */
    fun configure(path: String?) {
        synchronized(lock) {
            filePath = path
            loaded = false
        }
    }

    /** 清空内存统计并允许重新从文件加载（测试隔离用） */
    fun reset() {
        synchronized(lock) {
            totals.clear()
            noUsageCalls = 0
            sinceSummary = 0
            loaded = false
        }
    }

    /**
     * 记一次 LLM 调用（[OpenAIAPI] 每次拿到响应后调用）
     *
     * @param input 入参 token（含图片请求的视觉 token）；端点未返回记 0 并计一次 noUsage
     * @param output 出参 token
     */
    fun record(
        agent: String,
        conversation: String,
        channel: Channel,
        model: String,
        input: Long?,
        output: Long?
    ) {
        val inTok = input ?: 0L
        val outTok = output ?: 0L
        val reported = input != null || output != null
        synchronized(lock) {
            ensureLoadedLocked()
            val key = Key(agent, conversation, channel)
            totals[key] = (totals[key] ?: Agg(0, 0, 0)) + Agg(1, inTok, outTok)
            if (!reported) noUsageCalls++
            appendLocked(key, model, inTok, outTok, reported)
            sinceSummary++
            if (summaryEvery > 0 && sinceSummary >= summaryEvery) {
                sinceSummary = 0
                log.info("\n{}", summaryLocked())
            }
        }
    }

    /** 分维度汇总（跨重启累计）——按通道 / 按 agent / 按会话（前 N 个） */
    fun summary(topConversations: Int = 8): String = synchronized(lock) {
        ensureLoadedLocked()
        summaryLocked(topConversations)
    }

    // ------------------------------------------------------------------
    // 内部实现（一律在 [lock] 内调用）
    // ------------------------------------------------------------------

    /** 首次访问把 jsonl 历史读回内存——汇总跨重启累计（坏行跳过，不阻断） */
    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val path = filePath ?: return
        val file = File(path)
        if (!file.exists()) return
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val node = mapper.readTree(line)
                val key = Key(
                    agent = node.path("agent").asText("unknown"),
                    conversation = node.path("conversation").asText("-"),
                    channel = Channel.entries.firstOrNull { it.label == node.path("channel").asText() }
                        ?: Channel.TEXT
                )
                val agg = Agg(
                    calls = node.path("calls").asLong(1L),
                    input = node.path("in").asLong(0L),
                    output = node.path("out").asLong(0L)
                )
                totals[key] = (totals[key] ?: Agg(0, 0, 0)) + agg
                if (!node.path("reported").asBoolean(true)) noUsageCalls++
            }.onFailure {
                log.debug("token 用量行解析失败（跳过）: {}", it.message)
            }
        }
        log.debug("token 用量已加载 {} 行键值：{}", totals.size, file.name)
    }

    /** 追加一行明细（append-only；失败只记日志，绝不影响调用方的 LLM 链路） */
    private fun appendLocked(key: Key, model: String, input: Long, output: Long, reported: Boolean) {
        val path = filePath ?: return
        runCatching {
            val line = mapper.writeValueAsString(
                mapOf(
                    "ts" to java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    "agent" to key.agent,
                    "conversation" to key.conversation,
                    "channel" to key.channel.label,
                    "model" to model,
                    "calls" to 1,
                    "in" to input,
                    "out" to output,
                    "reported" to reported
                )
            )
            val file = File(path)
            file.parentFile?.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
        }.onFailure {
            log.warn("token 用量落盘失败（统计照常）: {}", it.message)
        }
    }

    private fun summaryLocked(topConversations: Int = 8): String {
        if (totals.isEmpty()) return "token 用量：暂无记录"
        val grand = totals.values.reduce { a, b -> a + b }
        val sb = StringBuilder()
        sb.append("token 用量汇总（累计 ${grand.calls} 次：入 ${grand.input} / 出 ${grand.output} / 合计 ${grand.total}）")
        if (noUsageCalls > 0) sb.append("；另有 $noUsageCalls 次端点未返回用量（按 0 计）")

        sb.append("\n- 按通道：").append(
            render(Channel.entries.map { ch -> ch.label to aggregate { k -> k.channel == ch } })
        )
        sb.append("\n- 按 agent：").append(
            render(totals.keys.map { it.agent }.distinct().sorted().map { a -> a to aggregate { k -> k.agent == a } })
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
        return sb.toString()
    }

    /** 按谓词聚合成一行「名称 次数 入/出/合」 */
    private fun render(rows: List<Pair<String, Agg>>): String =
        rows.joinToString("｜") { (name, agg) -> "$name ${fmt(agg)}" }

    private fun fmt(agg: Agg): String = "${agg.calls}次 入${agg.input}/出${agg.output}/合${agg.total}"

    private fun aggregate(predicate: (Key) -> Boolean): Agg =
        totals.filterKeys(predicate).values.reduceOrNull { a, b -> a + b } ?: Agg(0, 0, 0)
}

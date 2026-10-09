package org.dlucxoket.lucatbot.bot.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.dlucxoket.lucatbot.bot.persona.TimeFormat
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * token 用量**明细存储**——只管「一行一行地读写」，不关心聚合（SRP）
 *
 * 实现：
 * - [MySqlTokenUsageStore]（`bot/storage/mysql/`）：**生产用**，`token_usage` 表——
 *   按 `storage.type=mysql` 绑定（StorageConfig），请求/响应正文、耗时一并落库
 * - [JsonlTokenUsageStore]：`storage.type=json` 兜底，`data/token-usage.jsonl`
 *   append-only；mysql 首启时空表会把它整份导入（见 MySqlStorage.importFromJson）
 * - [InMemoryTokenUsageStore]：非 Spring 兜底实例与单测用，不碰磁盘
 *
 * 三个读口各司其职（大字段 request/response 只在详情里带）：
 * - [readAll]：聚合装载（跨重启累计）
 * - [readRecent]：明细列表（时间倒序，前端表格/轨迹左栏）
 * - [find]：单条**详情**（含发给 LLM 的真实请求与模型回复）
 */
interface TokenUsageStore {
    /** 追加一行——实现负责容错（失败记日志，**绝不冒泡**到 LLM 调用链路） */
    fun append(record: TokenUsageCall)

    /** 读回全部记录（聚合器首用时加载；实现负责跳过坏行） */
    fun readAll(): List<TokenUsageCall>

    /** 最近 N 条（时间倒序）——明细列表用；实现可只读轻量列 */
    fun readRecent(limit: Int): List<TokenUsageCall>

    /** 按 [TokenUsageCall.id] 取**完整**一行（含 request/response/error）；查不到 = null */
    fun find(id: Long?): TokenUsageCall?
}

/**
 * jsonl 存储（`storage.type=json`）
 *
 * 行格式（与历史版本兼容）：
 * `{"epoch":…,"ts":"yyyy-MM-dd HH:mm:ss","agent":…,"conversation":…,"channel":…,
 *   "model":…,"calls":1,"in":…,"out":…,"reported":…,"action":…,"firstRound":…,
 *   "durationMs":…,"request":…,"response":…,"error":…}`
 *
 * `id` = **行号**（append-only 不删行，编号稳定），详情按行号取。
 *
 * @param path 文件路径；**null = 只统计不落盘**（测试隔离用）
 */
class JsonlTokenUsageStore(private val path: String?) : TokenUsageStore {

    private val log = LoggerFactory.getLogger(JsonlTokenUsageStore::class.java)
    private val mapper = jacksonObjectMapper()
    private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override fun append(record: TokenUsageCall) {
        val target = path ?: return
        runCatching {
            val now = if (record.epoch > 0) record.epoch else System.currentTimeMillis()
            val line = mapper.writeValueAsString(
                buildMap<String, Any?> {
                    // epoch 供时间轴分桶（旧格式只有 ts 本地时间字符串，读时兜底解析）
                    put("epoch", now)
                    put("ts", LocalDateTime.ofInstant(Instant.ofEpochMilli(now), TimeFormat.ZONE).format(tsFormat))
                    put("agent", record.agent)
                    put("conversation", record.conversation)
                    put("channel", record.channel)
                    put("model", record.model)
                    put("calls", 1)
                    put("in", record.input)
                    put("out", record.output)
                    put("reported", record.reported)
                    // 决策标签（P5）：旧格式行没有这两项 → 读时回落 "-"/false
                    put("action", record.action)
                    put("firstRound", record.firstRound)
                    // 轨迹三件套（旧格式行没有 → 读时 null）
                    put("durationMs", record.durationMs)
                    put("request", record.request)
                    put("response", record.response)
                    put("error", record.error)
                }
            )
            val file = File(target)
            file.parentFile?.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
        }.onFailure {
            log.warn("token 用量落盘失败（统计照常）: {}", it.message)
        }
    }

    override fun readAll(): List<TokenUsageCall> = parseFile().map { it.second }

    override fun readRecent(limit: Int): List<TokenUsageCall> =
        parseFile().sortedByDescending { it.second.epoch }.take(limit).map { it.second }

    override fun find(id: Long?): TokenUsageCall? =
        parseFile().firstOrNull { it.first == id }?.second

    /** 逐行解析（行号 1 起 = 该行的 id；坏行跳过不占编号——编号即物理行号） */
    private fun parseFile(): List<Pair<Long, TokenUsageCall>> {
        val target = path ?: return emptyList()
        val file = File(target)
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
            if (line.isBlank()) null
            else runCatching {
                val id = index + 1L
                id to parse(line).copy(id = id)
            }.getOrNull()
        }.also {
            if (it.isNotEmpty()) log.debug("token 用量已读回 {} 行：{}", it.size, file.name)
        }
    }

    private fun parse(line: String): TokenUsageCall = parseLine(line)

    companion object {
        private val mapper = jacksonObjectMapper()
        private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * 解析一行 jsonl（**MySQL 存量迁移复用同一解析器**，两处口径不漂移）
         *
         * 旧格式（加 epoch 之前）只有本地时间字符串 ts——按 [TimeFormat.ZONE] 兜底
         * 解析成毫秒时间戳；解析不了就抛给调用方按坏行跳过
         */
        internal fun parseLine(line: String): TokenUsageCall {
            val node = mapper.readTree(line)
            val epoch = if (node.has("epoch")) {
                node.path("epoch").asLong(0L)
            } else {
                LocalDateTime.parse(node.path("ts").asText(), tsFormat)
                    .atZone(TimeFormat.ZONE).toInstant().toEpochMilli()
            }
            return TokenUsageCall(
                epoch = epoch,
                agent = node.path("agent").asText("unknown"),
                conversation = node.path("conversation").asText("-"),
                channel = node.path("channel").asText(TokenUsageChannel.TEXT.label),
                model = node.path("model").asText("unknown"),
                input = node.path("in").asLong(0L),
                output = node.path("out").asLong(0L),
                reported = node.path("reported").asBoolean(true),
                action = node.path("action").asText("-").ifBlank { "-" },
                firstRound = node.path("firstRound").asBoolean(false),
                durationMs = node.path("durationMs").takeIf { it.isNumber }?.asLong(),
                request = node.path("request").takeIf { it.isTextual }?.asText(),
                response = node.path("response").takeIf { it.isTextual }?.asText(),
                error = node.path("error").takeIf { it.isTextual }?.asText()
            )
        }
    }
}

/** 纯内存存储（非 Spring 兜底实例 / 单测：不碰磁盘）；id = 自增序号 */
class InMemoryTokenUsageStore : TokenUsageStore {
    private val rows = mutableListOf<TokenUsageCall>()
    private var seq = 0L

    override fun append(record: TokenUsageCall) {
        synchronized(rows) {
            seq++
            rows += record.copy(id = seq)
        }
    }

    override fun readAll(): List<TokenUsageCall> = synchronized(rows) { rows.toList() }

    override fun readRecent(limit: Int): List<TokenUsageCall> =
        synchronized(rows) { rows.sortedByDescending { it.epoch }.take(limit) }

    override fun find(id: Long?): TokenUsageCall? =
        synchronized(rows) { rows.firstOrNull { it.id == id } }
}

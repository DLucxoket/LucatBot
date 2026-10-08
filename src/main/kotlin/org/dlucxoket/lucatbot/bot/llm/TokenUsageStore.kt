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
 * 两个实现：
 * - [JsonlTokenUsageStore]：生产用，`data/token-usage.jsonl` append-only（崩溃不丢），
 *   首次读回让汇总**跨重启累计**；属观测数据，不进 `storage.type` 后端
 * - [InMemoryTokenUsageStore]：非 Spring 兜底实例与单测用，不碰磁盘
 */
interface TokenUsageStore {
    /** 追加一行——实现负责容错（失败记日志，**绝不冒泡**到 LLM 调用链路） */
    fun append(record: TokenUsageCall)

    /** 读回全部记录（聚合器首用时加载；实现负责跳过坏行） */
    fun readAll(): List<TokenUsageCall>
}

/**
 * jsonl 存储（**默认生产实现**）
 *
 * 行格式（与历史版本兼容）：
 * `{"epoch":…,"ts":"yyyy-MM-dd HH:mm:ss","agent":…,"conversation":…,"channel":…,
 *   "model":…,"calls":1,"in":…,"out":…,"reported":…}`
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
                mapOf(
                    // epoch 供时间轴分桶（旧格式只有 ts 本地时间字符串，读时兜底解析）
                    "epoch" to now,
                    "ts" to LocalDateTime.ofInstant(Instant.ofEpochMilli(now), TimeFormat.ZONE).format(tsFormat),
                    "agent" to record.agent,
                    "conversation" to record.conversation,
                    "channel" to record.channel,
                    "model" to record.model,
                    "calls" to 1,
                    "in" to record.input,
                    "out" to record.output,
                    "reported" to record.reported,
                    // 决策标签（P5）：旧格式行没有这两项 → 读时回落 "-"/false
                    "action" to record.action,
                    "firstRound" to record.firstRound
                )
            )
            val file = File(target)
            file.parentFile?.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
        }.onFailure {
            log.warn("token 用量落盘失败（统计照常）: {}", it.message)
        }
    }

    override fun readAll(): List<TokenUsageCall> {
        val target = path ?: return emptyList()
        val file = File(target)
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) null else runCatching { parse(line) }.getOrNull()
        }.also {
            if (it.isNotEmpty()) log.debug("token 用量已读回 {} 行：{}", it.size, file.name)
        }
    }

    /**
     * 解析一行 jsonl
     *
     * 旧格式（加 epoch 之前）只有本地时间字符串 ts——按 [TimeFormat.ZONE] 兜底
     * 解析成毫秒时间戳；解析不了就抛给调用方按坏行跳过
     */
    private fun parse(line: String): TokenUsageCall {
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
            firstRound = node.path("firstRound").asBoolean(false)
        )
    }
}

/** 纯内存存储（非 Spring 兜底实例 / 单测：不碰磁盘） */
class InMemoryTokenUsageStore : TokenUsageStore {
    private val rows = mutableListOf<TokenUsageCall>()

    override fun append(record: TokenUsageCall) {
        synchronized(rows) { rows += record }
    }

    override fun readAll(): List<TokenUsageCall> = synchronized(rows) { rows.toList() }
}

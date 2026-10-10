package org.dlucxoket.lucatbot.bot.storage.mysql

import org.dlucxoket.lucatbot.bot.llm.TokenUsageCall
import org.dlucxoket.lucatbot.bot.llm.TokenUsageChannel
import org.dlucxoket.lucatbot.bot.llm.TokenUsageStore
import java.sql.ResultSet

/**
 * token 用量明细——MySQL 实现（第九张表 `token_usage`，`storage.type=mysql` 生产后端）
 *
 * 与 [org.dlucxoket.lucatbot.bot.llm.JsonlTokenUsageStore] 实现同一接口，
 * StorageConfig 按 `storage.type` 绑定；写入即落库（append 不冒泡失败）。
 *
 * 大字段（`request`/`response` 发给 LLM 的真实请求与模型回复，MEDIUMTEXT）
 * **只在 [find] 详情里带**——[readAll]/[readRecent] 只读轻量列，聚合与列表
 * 不被正文拖慢。
 *
 * 存量迁移：`data/token-usage.jsonl` 在**表空时**整份导入（MySqlStorage.init，
 * 只跑一次），此后 MySQL 是唯一写入方，jsonl 只读留档。
 */
class MySqlTokenUsageStore(
    private val storage: MySqlStorage
) : TokenUsageStore {

    /** 轻量列（列表/聚合用，不含 request/response 正文） */
    private val LIGHT_COLUMNS =
        "id, epoch, agent, conversation, channel, model, input_tokens, output_tokens, " +
            "reported, action, first_round, duration_ms, error, cached_tokens"

    override fun append(record: TokenUsageCall) {
        runCatching {
            storage.update(
                INSERT,
                MySqlTime.of(if (record.epoch > 0) record.epoch else System.currentTimeMillis()),
                record.agent, record.conversation, record.channel, record.model,
                record.input, record.output, record.reported, record.action, record.firstRound,
                record.durationMs, record.request, record.response, record.error, record.cached
            )
        }.onFailure {
            // 观测数据绝不冒泡到 LLM 调用链路
            org.slf4j.LoggerFactory.getLogger(MySqlTokenUsageStore::class.java)
                .warn("token 用量落库失败（统计照常）: {}", it.message)
        }
    }

    override fun readAll(): List<TokenUsageCall> =
        storage.query("SELECT $LIGHT_COLUMNS FROM token_usage ORDER BY id", map = ::mapLight)

    override fun readRecent(limit: Int): List<TokenUsageCall> =
        storage.query(
            "SELECT $LIGHT_COLUMNS FROM token_usage ORDER BY epoch DESC, id DESC LIMIT ?",
            limit.coerceAtLeast(0), map = ::mapLight
        )

    override fun find(id: Long?): TokenUsageCall? =
        id?.let {
            storage.query("SELECT * FROM token_usage WHERE id = ?", it, map = ::mapFull).firstOrNull()
        }

    /** 轻量行（error 一并带：失败标记很短，列表要显示） */
    private fun mapLight(rs: ResultSet): TokenUsageCall = mapCommon(rs).copy(
        request = null, response = null, error = rs.getString("error")
    )

    /** 详情行（含真实请求与回复正文） */
    private fun mapFull(rs: ResultSet): TokenUsageCall = mapCommon(rs).copy(
        request = rs.getString("request"),
        response = rs.getString("response"),
        error = rs.getString("error")
    )

    private fun mapCommon(rs: ResultSet) = TokenUsageCall(
        id = rs.getLong("id"),
        epoch = MySqlTime.toEpochMs(rs, "epoch"),
        agent = rs.getString("agent") ?: "unknown",
        conversation = rs.getString("conversation") ?: "-",
        channel = rs.getString("channel") ?: TokenUsageChannel.TEXT.label,
        model = rs.getString("model") ?: "unknown",
        input = rs.getLong("input_tokens"),
        output = rs.getLong("output_tokens"),
        reported = rs.getBoolean("reported"),
        action = rs.getString("action")?.ifBlank { "-" } ?: "-",
        firstRound = rs.getBoolean("first_round"),
        durationMs = rs.getLong("duration_ms").let { if (rs.wasNull()) null else it },
        cached = rs.getLong("cached_tokens").let { if (rs.wasNull()) null else it }
    )

    /** StorageConfig 的 destroyMethod 兜底；短连接无池可关 */
    fun shutdown() {
        // no-op
    }

    companion object {
        internal const val INSERT =
            "INSERT INTO token_usage (epoch, agent, conversation, channel, model, " +
                "input_tokens, output_tokens, reported, action, first_round, " +
                "duration_ms, request, response, error, cached_tokens) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    }
}

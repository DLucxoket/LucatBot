package org.dlucxoket.lucatbot.bot.storage.mysql

import org.dlucxoket.lucatbot.bot.loop.ActionLogEntry
import org.dlucxoket.lucatbot.bot.loop.ActionLogRepository

/**
 * 操作日志仓库——MySQL 实现（第七张表 `action_log`）
 *
 * 结构化四列：`logged_at`（时间）/ `conversation_id`（所处会话）/
 * `action`（操作）/ `reason`（决策原因）。
 *
 * **只增不删**（持久化的执行日志不删行）：
 * - [appendEntries] 只批量 INSERT 新条目——历史全留，表就是全量执行日志；
 *   表里数据量小（每次 flush 只有几条增量），不删也无限可用
 * - [loadEntries] 用 `ORDER BY id DESC LIMIT N` 只取最近 [loadLimit] 行进
 *   内存滚动窗口（**在 SQL 端 LIMIT**，不靠删表瘦身）
 */
class MySqlActionLogRepository(
    private val storage: MySqlStorage,
    /** 启动恢复读取的行数上限（历史全在库里，只取最近这么多进内存窗口） */
    private val loadLimit: Int = 100
) : ActionLogRepository {

    override fun loadEntries(): List<ActionLogEntry> =
        storage.query(
            "SELECT logged_at, conversation_id, action, reason FROM action_log " +
                "ORDER BY id DESC LIMIT $loadLimit",
            map = { rs ->
                ActionLogEntry(
                    timestamp = MySqlTime.toEpochMs(rs, "logged_at"),
                    conversationId = rs.getString("conversation_id"),
                    action = rs.getString("action"),
                    reason = rs.getString("reason")
                )
            }
        ).asReversed() // DESC 取最新 → 翻回升序给 ActionLog（旧的在前）

    override fun appendEntries(newEntries: List<ActionLogEntry>) {
        if (newEntries.isEmpty()) return
        storage.connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(INSERT).use { ps ->
                    newEntries.forEach { e ->
                        ps.setObject(1, MySqlTime.of(e.timestamp))
                        ps.setObject(2, e.conversationId)
                        ps.setString(3, e.action)
                        ps.setObject(4, e.reason)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    /** StorageConfig 的 destroyMethod 兜底；短连接无池可关 */
    fun shutdown() {
        // no-op：写入即落库（防抖 flush 由 ActionLog 负责）
    }

    private companion object {
        /** 只 INSERT——**没有任何 DELETE**：历史行永不删除 */
        const val INSERT =
            "INSERT INTO action_log (logged_at, conversation_id, action, reason) VALUES (?,?,?,?)"
    }
}

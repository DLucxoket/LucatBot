package org.dlucxoket.lucatbot.bot.storage.mysql

import org.dlucxoket.lucatbot.bot.loop.ActionLogEntry
import org.dlucxoket.lucatbot.bot.loop.ActionLogRepository

/**
 * 操作日志仓库——MySQL 实现（第七张表 `action_log`）
 *
 * 结构化四列：`logged_at`（时间）/ `conversation_id`（所处会话）/
 * `action`（操作）/ `reason`（决策原因）。
 *
 * **整窗同步**：saveEntries = DELETE 全表 + 批量 INSERT 当前窗口（≤20 条、
 * 5s 防抖，开销可忽略）——表恒只存最近窗口，load 直接全表升序。
 * 表 id 仅做行序（全量删插会跳空，**无引用关系、跳空无害**——与 tags 不同）。
 */
class MySqlActionLogRepository(
    private val storage: MySqlStorage
) : ActionLogRepository {

    override fun loadEntries(): List<ActionLogEntry> =
        storage.query(
            "SELECT logged_at, conversation_id, action, reason FROM action_log ORDER BY id ASC",
            map = { rs ->
                ActionLogEntry(
                    timestamp = MySqlTime.toEpochMs(rs, "logged_at"),
                    conversationId = rs.getString("conversation_id"),
                    action = rs.getString("action"),
                    reason = rs.getString("reason")
                )
            }
        )

    override fun saveEntries(entries: List<ActionLogEntry>) {
        storage.connection().use { conn ->
            conn.autoCommit = false
            try {
                storage.update(conn, "DELETE FROM action_log")
                if (entries.isNotEmpty()) {
                    conn.prepareStatement(
                        "INSERT INTO action_log (logged_at, conversation_id, action, reason) VALUES (?,?,?,?)"
                    ).use { ps ->
                        entries.forEach { e ->
                            ps.setObject(1, MySqlTime.of(e.timestamp))
                            ps.setObject(2, e.conversationId)
                            ps.setString(3, e.action)
                            ps.setObject(4, e.reason)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
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
}

package org.dlucxoket.lucatbot.bot.storage.mysql

import org.dlucxoket.lucatbot.bot.chathistory.ReadStateRepository

/**
 * 已读水位仓库——MySQL 实现
 *
 * 表 `read_state(conversation_id PK, last_read_at)`（MySqlStorage 建表清单）。
 * upsert 语义（进会话即覆盖水位）；写入即落库，shutdown 为 no-op。
 */
class MySqlReadStateRepository(
    private val storage: MySqlStorage
) : ReadStateRepository {

    override fun lastReadAt(conversationId: String): Long? =
        storage.queryOne(
            "SELECT last_read_at AS t FROM read_state WHERE conversation_id = ?",
            conversationId
        ) { rs ->
            val t = rs.getTimestamp("t")
            if (t == null) null else t.time
        }

    override fun markRead(conversationId: String, atMs: Long) {
        storage.update(
            "INSERT INTO read_state (conversation_id, last_read_at) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE last_read_at = VALUES(last_read_at)",
            conversationId, MySqlTime.of(atMs)
        )
    }

    override fun allLastRead(): Map<String, Long> =
        storage.query(
            "SELECT conversation_id, last_read_at FROM read_state",
            map = { rs ->
                rs.getString("conversation_id") to (rs.getTimestamp("last_read_at")?.time ?: 0L)
            }
        ).toMap()

    /** StorageConfig 的 destroyMethod 兜底；短连接无池可关 */
    fun shutdown() {
        // no-op：写入即落库
    }
}

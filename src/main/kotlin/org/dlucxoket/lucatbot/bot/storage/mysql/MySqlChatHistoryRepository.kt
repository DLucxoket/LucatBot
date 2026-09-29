package org.dlucxoket.lucatbot.bot.storage.mysql

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryRepository
import org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord
import java.sql.ResultSet

/**
 * 聊天记录仓库——MySQL 实现
 *
 * 与 [org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository]
 * 实现同一接口，StorageConfig 按 `storage.type` 绑定，业务层无感知。
 *
 * **写入即落库**（短连接直写，无防抖）——`flush()`/`shutdown()` 是显式 no-op：
 * JSON 实现靠防抖攒批，MySQL 每条 UPDATE/INSERT 已经持久。
 * `findByConversation` 用「倒序 LIMIT n 再翻回正序」取最近 n 条，
 * 与 JSON 实现的 takeLast 语义一致；`before` 用索引列过滤。
 */
class MySqlChatHistoryRepository(
    private val storage: MySqlStorage
) : ChatHistoryRepository {

    override fun save(record: ChatMessageRecord) {
        storage.update(
            INSERT,
            record.id, record.conversationId, record.senderId, record.senderName, record.content,
            record.messageType.name, record.timestamp, record.messageId, record.isFromBot
        )
    }

    override fun findByConversation(
        conversationId: String,
        limit: Int,
        before: Long
    ): List<ChatMessageRecord> {
        val sql = StringBuilder(
            "SELECT * FROM (SELECT * FROM chat_history WHERE conversation_id = ?"
        )
        val params = mutableListOf<Any?>(conversationId)
        if (before > 0L) {
            sql.append(" AND timestamp_ms < ?")
            params += before
        }
        sql.append(" ORDER BY timestamp_ms DESC")
        if (limit > 0) {
            sql.append(" LIMIT ?")
            params += limit
        }
        sql.append(") t ORDER BY timestamp_ms ASC")

        return storage.query(sql.toString(), *params.toTypedArray(), map = ::mapRow)
    }

    override fun flush() {
        // no-op：写入即落库（见 KDoc）
    }

    /** StorageConfig 的 destroyMethod 兜底；短连接无池可关 */
    fun shutdown() {
        // no-op
    }

    private fun mapRow(rs: ResultSet) = ChatMessageRecord(
        id = rs.getString("id"),
        conversationId = rs.getString("conversation_id"),
        senderId = rs.getLong("sender_id"),
        senderName = rs.getString("sender_name"),
        content = rs.getString("content"),
        messageType = messageTypeOf(rs.getString("message_type")),
        timestamp = rs.getLong("timestamp_ms"),
        messageId = rs.getLong("message_id").let { if (rs.wasNull()) null else it },
        isFromBot = rs.getBoolean("is_from_bot")
    )

    private fun messageTypeOf(raw: String?): ChatMessageRecord.MessageType =
        runCatching { ChatMessageRecord.MessageType.valueOf(raw ?: "") }
            .getOrDefault(ChatMessageRecord.MessageType.TEXT)

    companion object {
        /** upsert（与 JSON 缓存覆盖语义一致：同 id 全字段覆盖） */
        internal const val INSERT =
            "INSERT INTO chat_history (id, conversation_id, sender_id, sender_name, content, " +
                "message_type, timestamp_ms, message_id, is_from_bot) VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE conversation_id=VALUES(conversation_id), " +
                "sender_id=VALUES(sender_id), sender_name=VALUES(sender_name), content=VALUES(content), " +
                "message_type=VALUES(message_type), timestamp_ms=VALUES(timestamp_ms), " +
                "message_id=VALUES(message_id), is_from_bot=VALUES(is_from_bot)"
    }
}

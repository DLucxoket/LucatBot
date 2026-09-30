package org.dlucxoket.lucatbot.bot.storage.mysql

import com.fasterxml.jackson.module.kotlin.readValue
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
            record.id, record.conversationId, record.senderId, record.senderName, record.senderNickname,
            record.imageUrls?.let { storage.mapper.writeValueAsString(it) },
            record.memorized,
            record.content, record.messageType.name, MySqlTime.of(record.timestamp),
            record.messageId, record.isFromBot
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
            sql.append(" AND sent_at < ?")
            params += MySqlTime.of(before)
        }
        sql.append(" ORDER BY sent_at DESC")
        if (limit > 0) {
            sql.append(" LIMIT ?")
            params += limit
        }
        sql.append(") t ORDER BY sent_at ASC")

        return storage.query(sql.toString(), *params.toTypedArray(), map = ::mapRow)
    }

    override fun findAll(): List<ChatMessageRecord> =
        storage.query("SELECT * FROM chat_history", map = ::mapRow)

    override fun lastBotSpeakTime(conversationId: String): Long? =
        storage.queryOne(
            "SELECT MAX(sent_at) AS t FROM chat_history " +
                "WHERE conversation_id = ? AND is_from_bot = 1",
            conversationId
        ) { rs ->
            val t = rs.getTimestamp("t")
            if (t == null) null else t.time
        }

    override fun maxTimestamp(conversationId: String): Long? =
        storage.queryOne(
            "SELECT MAX(sent_at) AS t FROM chat_history WHERE conversation_id = ?",
            conversationId
        ) { rs ->
            val t = rs.getTimestamp("t")
            if (t == null) null else t.time
        }

    override fun findByConversationSince(conversationId: String, since: Long): List<ChatMessageRecord> =
        storage.query(
            "SELECT * FROM chat_history WHERE conversation_id = ? AND sent_at > ? ORDER BY sent_at ASC",
            conversationId, MySqlTime.of(since),
            map = ::mapRow
        )

    override fun delete(id: String) {
        storage.update("DELETE FROM chat_history WHERE id = ?", id)
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
        senderNickname = rs.getString("sender_nickname"),
        imageUrls = rs.getString("image_urls")?.let { json ->
            runCatching { storage.mapper.readValue<List<String>>(json) }.getOrNull()
        },
        memorized = rs.getBoolean("memorized"),
        content = rs.getString("content"),
        messageType = messageTypeOf(rs.getString("message_type")),
        timestamp = MySqlTime.toEpochMs(rs, "sent_at"),
        messageId = rs.getLong("message_id").let { if (rs.wasNull()) null else it },
        isFromBot = rs.getBoolean("is_from_bot")
    )

    private fun messageTypeOf(raw: String?): ChatMessageRecord.MessageType =
        runCatching { ChatMessageRecord.MessageType.valueOf(raw ?: "") }
            .getOrDefault(ChatMessageRecord.MessageType.TEXT)

    companion object {
        /** save 用 upsert（与 JSON 缓存覆盖语义一致：同 id 全字段覆盖） */
        internal const val INSERT =
            "INSERT INTO chat_history (id, conversation_id, sender_id, sender_name, sender_nickname, image_urls, memorized, content, " +
                "message_type, sent_at, message_id, is_from_bot) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE conversation_id=VALUES(conversation_id), " +
                "sender_id=VALUES(sender_id), sender_name=VALUES(sender_name), " +
                "sender_nickname=VALUES(sender_nickname), image_urls=VALUES(image_urls), " +
                "memorized=VALUES(memorized), content=VALUES(content), " +
                "message_type=VALUES(message_type), sent_at=VALUES(sent_at), " +
                "message_id=VALUES(message_id), is_from_bot=VALUES(is_from_bot)"

        /** json 同步用：主键冲突即跳过（不覆盖库里已更新的行）——由 [INSERT] 派生防漂移 */
        internal val INSERT_IGNORE: String
            get() = INSERT.substringBefore(" ON DUPLICATE KEY UPDATE")
                .replaceFirst("INSERT ", "INSERT IGNORE ")
    }
}

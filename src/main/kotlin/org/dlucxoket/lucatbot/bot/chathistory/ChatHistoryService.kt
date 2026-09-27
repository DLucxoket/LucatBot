package org.dlucxoket.lucatbot.bot.chathistory

import org.dlucxoket.lucatbot.model.event.message.MessageEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 聊天记录服务
 *
 * **模拟真人的聊天记忆**：
 * - 记住每条消息（谁、什么时候、说了什么）
 * - 标记已读/未读（像 QQ 未读红点）
 * - 支持查历史（"上次他说了啥来着？"）
 *
 * **OOP 设计**：
 * - 依赖 [ChatHistoryRepository] 接口（DIP）
 * - 单一职责：聊天记录的存取、查询、已读管理
 */
@Component
class ChatHistoryService(
    private val repository: ChatHistoryRepository
) {
    private val log = LoggerFactory.getLogger(ChatHistoryService::class.java)

    /**
     * 记录一条收到的消息
     */
    fun recordMessage(event: MessageEvent, content: String) {
        val record = ChatMessageRecord(
            conversationId = event.conversationId,
            senderId = event.sender.qqNumber,
            senderName = event.sender.nickname,
            content = content,
            messageType = detectMessageType(content),
            timestamp = event.timestamp,
            isRead = false,
            isFromBot = false
        )
        repository.save(record)
    }

    /**
     * 记录一条机器人发出的消息
     */
    fun recordBotReply(conversationId: String, content: String) {
        val record = ChatMessageRecord(
            conversationId = conversationId,
            senderId = 0L,
            senderName = "bot",
            content = content,
            messageType = detectMessageType(content),
            timestamp = System.currentTimeMillis(),
            isRead = true,
            isFromBot = true
        )
        repository.save(record)
    }

    /**
     * 获取某个会话的聊天历史
     */
    fun getHistory(conversationId: String, limit: Int = 20): List<ChatMessageRecord> {
        return repository.findByConversation(conversationId, limit)
    }

    /**
     * 获取聊天历史摘要（注入 LLM prompt）
     */
    fun getHistorySummary(conversationId: String, limit: Int = 10): String {
        val records = getHistory(conversationId, limit)
        if (records.isEmpty()) return "（新会话）"

        return records.joinToString("\n") { record ->
            val role = if (record.isFromBot) "我" else record.senderName
            "$role: ${record.content}"
        }
    }

    /**
     * 获取未读消息
     */
    fun getUnread(conversationId: String): List<ChatMessageRecord> {
        return repository.findUnread(conversationId)
    }

    /**
     * 获取所有未读会话
     */
    fun getUnreadConversations(): List<String> {
        return repository.findUnreadConversations()
    }

    /**
     * 获取未读概况（会话 → 条数）
     */
    fun getUnreadSummary(): Map<String, Int> {
        return repository.findAllUnread()
            .groupBy { it.conversationId }
            .mapValues { it.value.size }
    }

    /**
     * 标记会话已读
     */
    fun markAsRead(conversationId: String) {
        repository.markAsRead(conversationId)
    }

    /**
     * 根据发送者查消息
     */
    fun getBySender(senderId: Long): List<ChatMessageRecord> {
        return repository.findBySender(senderId)
    }

    /**
     * 持久化
     */
    fun flush() {
        repository.flush()
    }

    /**
     * 检测消息类型
     */
    private fun detectMessageType(content: String): ChatMessageRecord.MessageType {
        return when {
            content.contains("[图片]") && content.contains("[表情]") -> ChatMessageRecord.MessageType.MIXED
            content.contains("[图片]") -> ChatMessageRecord.MessageType.IMAGE
            content.contains("[表情]") -> ChatMessageRecord.MessageType.FACE
            content.contains("[表情包]") -> ChatMessageRecord.MessageType.MEME
            content.contains("@") -> ChatMessageRecord.MessageType.AT
            else -> ChatMessageRecord.MessageType.TEXT
        }
    }
}

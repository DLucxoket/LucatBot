package org.dlucxoket.lucatbot.bot.chathistory

/**
 * 聊天记录条目
 *
 * **模拟真人的聊天记忆**：
 * - 记住每条消息的内容、发送者、时间
 * - 标记已读/未读（像 QQ 的未读红点）
 * - 按会话组织（群聊/私聊）
 * - 支持查询历史记录
 *
 * @param id 记录 ID
 * @param conversationId 会话 ID（如 "group:123" 或 "private:456"）
 * @param senderId 发送者 QQ 号
 * @param senderName 发送者昵称
 * @param content 消息内容（含 [图片] 等标记）
 * @param messageType 消息类型（text/image/face 等）
 * @param timestamp 发送时间戳（毫秒）
 * @param isRead 是否已读
 * @param isFromBot 是否是机器人自己发的
 */
data class ChatMessageRecord(
    val id: String = java.util.UUID.randomUUID().toString(),

    /** 会话 ID */
    val conversationId: String = "",

    /** 发送者 QQ 号 */
    val senderId: Long = 0L,

    /** 发送者昵称 */
    val senderName: String = "",

    /** 消息内容 */
    val content: String = "",

    /** 消息类型 */
    val messageType: MessageType = MessageType.TEXT,

    /** 发送时间戳（毫秒） */
    val timestamp: Long = System.currentTimeMillis(),

    /** 是否已读 */
    val isRead: Boolean = false,

    /** 是否是机器人发的 */
    val isFromBot: Boolean = false
) {
    enum class MessageType {
        TEXT,
        IMAGE,
        FACE,
        MEME,
        AT,
        POKE,
        MIXED
    }
}

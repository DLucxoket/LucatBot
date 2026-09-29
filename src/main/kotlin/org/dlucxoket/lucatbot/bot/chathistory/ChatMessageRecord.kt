package org.dlucxoket.lucatbot.bot.chathistory

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 聊天记录条目
 *
 * **模拟真人的聊天记忆**：
 * - 记住每条消息的内容、发送者、时间
 * - 按会话组织（群聊/私聊）
 * - 支持查询历史记录
 *
 * 未读/已读不在持久化模型里：由 `LucatBot.unreadQueue` 维护。
 *
 * @param id 记录 ID
 * @param conversationId 会话 ID（如 "group:123" 或 "private:456"）
 * @param senderId 发送者 QQ 号
 * @param senderName 发送者昵称
 * @param content 消息内容（含 [图片] 等标记）
 * @param messageType 消息类型（text/image/face 等）
 * @param timestamp 发送时间戳（毫秒）
 * @param isFromBot 是否是机器人自己发的
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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

    /**
     * OneBot message_id（被引用解析的键：`回复 #id` 靠它定位到具体消息）
     *
     * 来源：实时事件与发送回执（爬楼 DTO 未映射该字段）；缺失为 null，
     * 读取折叠时会把爬楼孪生记录的 id 合并到保留记录上。旧数据无此字段。
     */
    val messageId: Long? = null,

    /** 是否是机器人发的 */
    val isFromBot: Boolean = false
) {
    /** 消息内容类型：纯文本 / 图片 / 表情 / 表情包 / @ / 混合 */
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

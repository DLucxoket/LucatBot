package org.dlucxoket.lucatbot.model.event.message

/**
 * 私聊消息事件（私聊场景的抽象基类）
 *
 * 表示在 QQ 私聊窗口中收到的消息。
 * 是 [FriendMessageEvent] 等具体私聊事件的父类，
 * 预留扩展空间（如陌生人消息等）。
 *
 * @param message   私聊消息内容
 * @param sender    发送者
 * @param timestamp 消息时间戳
 * @param messageId OneBot message_id（可空）
 */
open class PrivateMessageEvent(
    message: org.dlucxoket.lucatbot.model.message.Message,
    sender: org.dlucxoket.lucatbot.model.user.User,
    timestamp: Long,
    messageId: Long? = null
) : org.dlucxoket.lucatbot.model.event.message.MessageEvent(message, sender, timestamp, messageId) {
    /** 拼装走 [org.dlucxoket.lucatbot.model.session.ConversationId]（协议唯一拼装点） */
    override val conversationId: String = org.dlucxoket.lucatbot.model.session.ConversationId.private(sender.qqNumber).asKey()
}

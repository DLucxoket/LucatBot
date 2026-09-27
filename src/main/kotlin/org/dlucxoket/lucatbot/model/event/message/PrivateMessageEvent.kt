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
 */
open class PrivateMessageEvent(
    message: org.dlucxoket.lucatbot.model.message.Message,
    sender: org.dlucxoket.lucatbot.model.user.User,
    timestamp: Long
) : org.dlucxoket.lucatbot.model.event.message.MessageEvent(message, sender, timestamp) {
    override val conversationId: String = "private:${sender.qqNumber}"
}

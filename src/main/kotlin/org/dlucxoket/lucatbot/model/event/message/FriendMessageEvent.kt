package org.dlucxoket.lucatbot.model.event.message

/**
 * 好友消息事件
 *
 * 特指来自 QQ 好友的私聊消息（区别于陌生人私聊等）。
 *
 * @param message   好友发送的消息内容
 * @param sender    发送者
 * @param timestamp 消息时间戳
 * @param messageId OneBot message_id（可空）
 */
class FriendMessageEvent(
    message: org.dlucxoket.lucatbot.model.message.Message,
    sender: org.dlucxoket.lucatbot.model.user.User,
    timestamp: Long,
    messageId: Long? = null
) : org.dlucxoket.lucatbot.model.event.message.PrivateMessageEvent(message, sender, timestamp, messageId) {
}

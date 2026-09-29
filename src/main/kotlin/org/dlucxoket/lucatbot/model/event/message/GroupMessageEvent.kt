package org.dlucxoket.lucatbot.model.event.message

/**
 * 群消息事件
 *
 * 表示在 QQ 群聊中收到的消息。
 *
 * @param message   群消息内容
 * @param sender    发送者
 * @param group     所在群组
 * @param timestamp 消息时间戳
 * @param messageId OneBot message_id（可空）
 */
class GroupMessageEvent(
    message: org.dlucxoket.lucatbot.model.message.Message,
    sender: org.dlucxoket.lucatbot.model.user.User,
    val group: org.dlucxoket.lucatbot.model.group.Group,
    timestamp: Long,
    messageId: Long? = null
) : org.dlucxoket.lucatbot.model.event.message.MessageEvent(message, sender, timestamp, messageId) {
    override val conversationId: String = "group:${group.groupNumber}"
}

package org.dlucxoket.lucatbot.model.event.message

import org.dlucxoket.lucatbot.model.event.Event
import org.dlucxoket.lucatbot.model.message.Message
import org.dlucxoket.lucatbot.model.user.User

/**
 * 消息事件——所有消息类事件的抽象基类
 *
 * 持有一个 [org.dlucxoket.lucatbot.model.message.Message] 实例，
 * 表示"某条消息在某个上下文中产生"。
 *
 * 子类按消息来源区分（私聊、群聊、好友等），
 * 消息内容本身由 Message 子类表达（文本、图片、表情等）。
 *
 * @param message   本事件携带的消息对象
 * @param sender    发送者用户信息
 * @param timestamp 消息时间戳（毫秒）
 * @param messageId OneBot message_id（跨入档与引用解析的稳定标识；
 *                  事件源未提供时为 null）
 */
abstract class MessageEvent(
    val message: Message,
    val sender: User,
    val timestamp: Long,
    val messageId: Long? = null
): Event {
    /**
     * 会话 ID（子类实现，如 "group:123" 或 "private:456"）
     */
    abstract val conversationId: String
}

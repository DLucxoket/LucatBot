package org.dlucxoket.lucatbot.model.message

/**
 * 对话消息（ChatMessage）
 *
 * 表示一条包含若干消息片段（[MessageMember]）的复合消息。
 *
 * @param messageMembers 消息成员列表，按顺序排列
 */
open class ChatMessage(
    val messageMembers: List<org.dlucxoket.lucatbot.model.message.member.MessageMember>
) : org.dlucxoket.lucatbot.model.message.Message {

    /**
     * 是否 @全体成员
     */
    fun isAtAll(): Boolean {
        return messageMembers.any {
            it is org.dlucxoket.lucatbot.model.message.member.AtMessageMember && it.isAtAll()
        }
    }

    /**
     * 提取纯文本内容
     */
    fun extractText(): String {
        return messageMembers
            .filterIsInstance<org.dlucxoket.lucatbot.model.message.member.TextMessageMember>()
            .joinToString("") { it.text }
    }
}

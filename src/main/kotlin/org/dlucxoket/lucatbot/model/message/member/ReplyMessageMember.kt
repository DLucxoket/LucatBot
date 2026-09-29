package org.dlucxoket.lucatbot.model.message.member

/**
 * 回复消息成员（Reply）
 *
 * 表示消息中「回复了某条消息」的片段。
 *
 * @param replyToId 被回复消息的 OneBot message_id
 * @param replyToName 被回复发送者的昵称（协议可能不带，可空）
 */
class ReplyMessageMember(
    val replyToId: Long,
    val replyToName: String? = null
) : MessageMember {
}

package org.dlucxoket.lucatbot.model.message

/**
 * 对话消息（ChatMessage）
 *
 * 表示一条包含若干消息片段（[MessageMember]）的复合消息。
 * 例如一条 QQ 消息可能是 "文字 + @某人 + 图片"，每个部分是一个 MessageMember。
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
     * 提取纯文本内容（只取文本片段）
     */
    fun extractText(): String {
        return messageMembers
            .filterIsInstance<org.dlucxoket.lucatbot.model.message.member.TextMessageMember>()
            .joinToString("") { it.text }
    }

    /**
     * 自然语言描述整条消息（含非文本内容）
     *
     * **模拟真人感知消息的方式**：
     * - 纯文本 → 原文
     * - 有图片 → 文本 + [图片] 标记（LLM 可据此自然回应）
     * - 有表情 → 文本 + [表情] 标记
     * - 有 @ → 文本 + @某人
     * - 有表情包 → 文本 + [表情包]
     *
     * 用于注入 LLM prompt，让 LLM 像真人一样"看到"完整消息。
     */
    fun describe(): String {
        return messageMembers.joinToString(" ") { member ->
            when (member) {
                is org.dlucxoket.lucatbot.model.message.member.TextMessageMember -> member.text
                is org.dlucxoket.lucatbot.model.message.member.AtMessageMember ->
                    if (member.isAtAll()) "@全体成员" else "@${member.targetQqNumber}"
                is org.dlucxoket.lucatbot.model.message.member.ImageMessageMember -> "[图片]"
                is org.dlucxoket.lucatbot.model.message.member.FaceMessageMember -> "[表情]"
                is org.dlucxoket.lucatbot.model.message.member.MemeMessageMember -> "[表情包]"
                else -> "[消息]"
            }
        }.trim()
    }
}

package org.dlucxoket.lucatbot.model.message.member

/**
 * @提及消息成员
 *
 * 表示消息中的 @某人 片段。
 *
 * @param targetQqNumber 被 @ 的目标 QQ 号
 * @param targetName 被 @ 者的昵称/群名片（协议可能不带，可空）
 */
class AtMessageMember(
    val targetQqNumber: Long,
    val targetName: String? = null
) : org.dlucxoket.lucatbot.model.message.member.MessageMember {
    /**
     * 是否 @全体成员
     */
    fun isAtAll(): Boolean {
        return targetQqNumber == -1L || targetQqNumber == 0L
    }

    /**
     * 描述用显示名：有昵称则「昵称(QQ)」，否则「QQ」
     */
    fun describeTarget(): String {
        val name = targetName?.takeIf { it.isNotBlank() }
        return if (name != null) "$name($targetQqNumber)" else "$targetQqNumber"
    }
}

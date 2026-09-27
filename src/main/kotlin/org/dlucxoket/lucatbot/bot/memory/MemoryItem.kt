package org.dlucxoket.lucatbot.bot.memory

/**
 * 记忆条目
 *
 * **模拟真人的记忆**：
 * - 对每个人有独特的记忆和看法
 * - 跨会话（群聊→私聊都能记得）
 * - 能联系不同的人（"A和B关系不错"）
 *
 * 记忆类型决定记忆的性质：
 * - FACT：客观事实（"他叫张三"）
 * - OPINION：主观印象（"这个人很有趣"）
 * - EVENT：事件记忆（"上次他帮了我"）
 * - RELATION：人际关系（"他和李四是朋友"）
 */
data class MemoryItem(
    /** 记忆 ID（唯一标识） */
    val id: String = java.util.UUID.randomUUID().toString(),

    /** 关联的用户 QQ 号（记忆的主体） */
    val userId: Long = 0L,

    /** 记忆类型 */
    val type: MemoryType = MemoryType.FACT,

    /** 记忆内容（自然语言描述） */
    val content: String = "",

    /** 关联的其他用户（用于人际关系记忆） */
    val relatedUserIds: List<Long> = emptyList(),

    /** 记忆标签（用于检索） */
    val tags: List<String> = emptyList(),

    /** 记忆强度 0.0-1.0（随时间衰减，越重要越持久） */
    val strength: Double = 1.0,

    /** 创建时间 */
    val createdAt: Long = System.currentTimeMillis(),

    /** 最后一次被回忆的时间 */
    val lastRecalledAt: Long = System.currentTimeMillis()
) {
    enum class MemoryType {
        /** 客观事实（名字、生日等） */
        FACT,

        /** 主观印象/看法 */
        OPINION,

        /** 事件记忆（发生过什么） */
        EVENT,

        /** 人际关系（A和B的关系） */
        RELATION
    }
}

package org.dlucxoket.lucatbot.bot.memory

/**
 * 记忆主体——记忆键空间的隔离单位
 *
 * 修复「群号与 QQ 号共用 userId」的键空间混淆：`group:123` 与 `private:123`
 * 曾都写成 `123L`，导致群记忆被当成"这个人的个人记忆"注入 prompt。
 *
 * 记忆一律按 `(type, id)` 二元组存取：同号的个人 QQ 与群聊互不污染。
 *
 * @param type 主体类型（USER=个人，GROUP=群）
 * @param id USER=个人 QQ 号；GROUP=群号
 */
data class MemorySubject(
    val type: Type,
    val id: Long
) {
    /** 主体类型：USER=个人（QQ 号），GROUP=群（群号） */
    enum class Type {
        /** 个人（QQ 号） */
        USER,

        /** 群聊（群号） */
        GROUP
    }

    override fun toString(): String = "${type.name.lowercase()}:$id"

    companion object {
        fun user(id: Long) = MemorySubject(Type.USER, id)

        fun group(id: Long) = MemorySubject(Type.GROUP, id)

        /**
         * 从会话 ID（`private:x` / `group:x`）推导记忆主体。
         *
         * 解析失败（无 id 或未知前缀）按 USER 兜底，与历史行为兼容。
         */
        fun fromConversation(conversationId: String): MemorySubject {
            val id = conversationId.substringAfter(":").toLongOrNull() ?: 0L
            return if (conversationId.startsWith("group:")) group(id) else user(id)
        }
    }
}

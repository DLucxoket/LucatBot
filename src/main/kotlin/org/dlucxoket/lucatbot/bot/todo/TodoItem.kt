package org.dlucxoket.lucatbot.bot.todo

/**
 * 待办条目
 *
 * **模拟真人的待办清单**：
 * - 用户托付的事记下来，之后的会话里能想起来
 * - 完成后标记 done（不删除，保留历史）
 *
 * @param id 唯一标识（UUID）
 * @param content 待办内容（自然语言）
 * @param createdAt 创建时间
 * @param done 是否已完成
 * @param conversationId 可选关联会话（group:xxx / private:xxx）
 */
data class TodoItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val done: Boolean = false,
    val conversationId: String? = null
)

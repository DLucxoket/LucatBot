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
 * @param completedAt 完成时间（epoch 毫秒；未完成 = null）——prompt 展示
 *   「最近已完成」时按它排序，也让人一眼看到办完的时间
 */
data class TodoItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val done: Boolean = false,
    val conversationId: String? = null,
    val completedAt: Long? = null
)

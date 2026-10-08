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
 * @param createdAt 创建时间（**已有字段**——面板「建于…」与 prompt 行尾的日期都取它）
 * @param done 是否已完成
 * @param conversationId 可选关联会话（group:xxx / private:xxx）
 * @param completedAt 完成时间（epoch 毫秒；未完成 = null）——prompt 展示
 *   「最近已完成」时按它排序，也让人一眼看到办完的时间
 * @param plannedAt **预计执行时间**（epoch 毫秒；未设定 = null）——面板可设/可清、
 *   `todo_add` 也可由 LLM 直接带；prompt 行里展示成「预计 MM-dd HH:mm」，
 *   机器人据此判断「这件该做了吗」
 */
data class TodoItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val done: Boolean = false,
    val conversationId: String? = null,
    val completedAt: Long? = null,
    val plannedAt: Long? = null
)

package org.dlucxoket.lucatbot.bot.todo

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 待办清单服务
 *
 * **模拟真人的待办清单**：
 * - 对话中托付的事记下来（`todo_add`），之后注入两个主流程智能体的 prompt
 * - 事后能标记完成（`todo_done`，按 id 或内容匹配）
 * - 注入用 [pendingSummary]（多行 `- [ ] (id前4位) content`）
 *
 * **OOP 设计**：
 * - 依赖 [TodoRepository] 接口（DIP）
 * - 单一职责：待办的业务规则（去重、匹配完成），不关心存储细节
 */
@Component
class TodoService(
    private val repository: TodoRepository
) {
    private val log = LoggerFactory.getLogger(TodoService::class.java)

    /**
     * 新增一条待办
     *
     * 去重：pending 中已有相同 content 则不重复加，直接返回已有条目。
     *
     * @param content 待办内容
     * @param conversationId 可选关联会话
     */
    fun add(content: String, conversationId: String? = null): TodoItem {
        val trimmed = content.trim()
        repository.findAll()
            .firstOrNull { !it.done && it.content == trimmed }
            ?.let { return it }

        val item = TodoItem(content = trimmed, conversationId = conversationId)
        repository.save(item)
        log.info("新增待办: ${item.id.take(4)} $trimmed")
        return item
    }

    /**
     * 标记完成
     *
     * 先按 id 精确匹配；否则按 pending 中 content 包含匹配。
     *
     * @return 是否找到并标完成
     */
    fun complete(idOrContent: String): Boolean {
        if (idOrContent.isBlank()) return false
        val todos = repository.findAll()
        val target = todos.firstOrNull { !it.done && it.id == idOrContent }
            ?: todos.firstOrNull { !it.done && it.content.contains(idOrContent) }
            ?: return false
        repository.save(target.copy(done = true))
        log.info("完成待办: ${target.id.take(4)} ${target.content}")
        return true
    }

    /**
     * 所有未完成的待办（按创建时间升序）
     */
    fun pending(): List<TodoItem> {
        return repository.findAll()
            .filter { !it.done }
            .sortedBy { it.createdAt }
    }

    /**
     * 待办摘要（注入 LLM prompt，多行）
     *
     * 格式：`- [ ] (id前4位) content`；无待办返回「（无待办）」。
     */
    fun pendingSummary(): String {
        val items = pending()
        if (items.isEmpty()) return "（无待办）"
        return items.joinToString("\n") { "- [ ] (${it.id.take(4)}) ${it.content}" }
    }

    /**
     * 持久化到文件
     */
    fun flush() {
        repository.flush()
    }
}

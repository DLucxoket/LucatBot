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
     * 找未完成待办（[complete] 与待办结算共用的匹配）
     *
     * 先按 id 精确匹配（**id 前缀也算**——prompt 里只展示 id 前 4 位，LLM 照抄
     * 的就是前缀）；否则按内容包含匹配。
     *
     * @return 匹配到的未完成条目；无匹配 / 引用空白 → null
     */
    fun findPending(idOrContent: String): TodoItem? {
        if (idOrContent.isBlank()) return null
        val pending = repository.findAll().filter { !it.done }
        return pending.firstOrNull {
            it.id == idOrContent || (idOrContent.length >= 4 && it.id.startsWith(idOrContent))
        } ?: pending.firstOrNull { it.content.contains(idOrContent) }
    }

    /**
     * 标记完成
     *
     * 先按 id 精确匹配；否则按 pending 中 content 包含匹配（见 [findPending]）。
     *
     * @return 是否找到并标完成
     */
    fun complete(idOrContent: String): Boolean {
        val target = findPending(idOrContent) ?: return false
        repository.save(target.copy(done = true, completedAt = System.currentTimeMillis()))
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
     * 注入 prompt 的待办摘要：未完成清单 + **最近完成的几条**（`- [x] …`）
     *
     * 「办完的事也看得见」——此前只注入未完成项，`todo_done` 一漏标（实盘：
     * 在群里看完就解析失败兜底退出，根本没机会输出），机器人被问「你不是去
     * 看过了吗」时仍一脸没办过的样子。完成项按 [TodoItem.completedAt] 倒序取
     * 最近 [doneLimit] 条（历史完成不删，保留痕迹）。
     */
    fun summaryForPrompt(doneLimit: Int = 5): String {
        val pending = pendingSummary()
        val done = recentDone(doneLimit)
        if (done.isEmpty()) return pending
        val doneLines = done.joinToString("\n") { "- [x] (${it.id.take(4)}) ${it.content}" }
        return pending + "\n最近已完成（办过的事，别再当成没做）：\n" + doneLines
    }

    /**
     * 最近完成的待办（按 [TodoItem.completedAt] 倒序）——prompt 与 **Web 面板**
     * 共用同一排序口径（历史完成不删，保留痕迹）
     */
    fun recentDone(limit: Int = 5): List<TodoItem> =
        repository.findAll().filter { it.done }
            .sortedByDescending { it.completedAt ?: it.createdAt }
            .take(limit)

    /**
     * 持久化到文件
     */
    fun flush() {
        repository.flush()
    }
}

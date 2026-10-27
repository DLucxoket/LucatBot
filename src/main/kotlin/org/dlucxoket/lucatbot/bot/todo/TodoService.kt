package org.dlucxoket.lucatbot.bot.todo

import org.dlucxoket.lucatbot.bot.persona.TimeFormat
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
     * 去重：pending 中已有相同 content 则不重复加（**但会顺带补上新给的预计时间**，
     * 别把计划丢掉），直接返回该条目。
     *
     * @param content 待办内容
     * @param conversationId 可选关联会话
     * @param plannedAt 预计执行时间（epoch 毫秒；可空）
     */
    fun add(content: String, conversationId: String? = null, plannedAt: Long? = null): TodoItem {
        val trimmed = content.trim()
        repository.findAll()
            .firstOrNull { !it.done && it.content == trimmed }
            ?.let { existing ->
                if (plannedAt != null && existing.plannedAt != plannedAt) {
                    val updated = existing.copy(plannedAt = plannedAt)
                    repository.save(updated)
                    return updated
                }
                return existing
            }

        val item = TodoItem(content = trimmed, conversationId = conversationId, plannedAt = plannedAt)
        repository.save(item)
        log.info(
            "新增待办: {} {}{}",
            item.id.take(4), trimmed,
            plannedAt?.let { "（预计 ${TimeFormat.listLine(it)}）" } ?: ""
        )
        return item
    }

    /**
     * 设置 / 清除**预计执行时间**（面板用）
     *
     * 匹配口径与 [cancel] 一致：id 精确 / ≥4 位前缀，**不做 content 模糊匹配**
     *
     * @param epoch 目标时间（毫秒）；**null = 清除预计**
     * @return 更新后的条目；无匹配 → null（幂等）
     */
    fun setPlannedAt(id: String, epoch: Long?): TodoItem? {
        if (id.isBlank()) return null
        val all = repository.findAll()
        val target = all.firstOrNull { it.id == id }
            ?: all.firstOrNull { id.length >= 4 && it.id.startsWith(id) }
            ?: return null
        repository.save(target.copy(plannedAt = epoch))
        log.info(
            "{}待办预计时间: {} {}",
            if (epoch == null) "清除" else "设置", target.id.take(4),
            epoch?.let { TimeFormat.listLine(it) } ?: ""
        )
        return target.copy(plannedAt = epoch)
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
     * **取消**一条待办（面板用）——从清单里删除，不是标完成
     *
     * 匹配口径比 [complete] 收窄：只认 **id 精确 / id 前缀**（≥4 位），
     * **不做 content 模糊匹配**——取消是破坏性操作，按内容猜容易误删。
     *
     * @return 被取消的条目；无匹配 → null（幂等，重复取消不报错）
     */
    fun cancel(id: String): TodoItem? {
        if (id.isBlank()) return null
        val all = repository.findAll()
        val target = all.firstOrNull { it.id == id }
            ?: all.firstOrNull { id.length >= 4 && it.id.startsWith(id) }
            ?: return null
        repository.delete(target.id)
        log.info("取消待办: ${target.id.take(4)} ${target.content}")
        return target
    }

    /**
     * 批量取消**全部未完成**待办（面板「清空未完成」——治「后台一直挂着 N 条
     * 陈年待办」：那些条目每轮都注入 prompt，白烧 token 还稀释行动理由）
     *
     * 只删未完成项（已完成是历史痕迹，保留）；返回被删条目供日志与面板回显
     */
    fun cancelAllPending(): List<TodoItem> {
        val targets = pending()
        targets.forEach { repository.delete(it.id) }
        if (targets.isNotEmpty()) log.info("清空未完成待办 ${targets.size} 条")
        return targets
    }

    /**
     * 待办摘要（注入 LLM prompt，多行）
     *
     * 格式：`- [ ] (id前4) content（MM-dd 建 · 预计 MM-dd HH:mm）`——**创建时间**与
     * **预计执行时间**都进 prompt（机器人据此判断「这件放了多久」「是不是该做了」）；
     * 没设预计就只带建的日期。空清单 → 「（无待办）」。
     */
    fun pendingSummary(): String {
        val items = pending()
        if (items.isEmpty()) return "（无待办）"
        return items.joinToString("\n") { item ->
            val stamp = buildString {
                append("（").append(TimeFormat.monthDay(item.createdAt)).append(" 建")
                item.plannedAt?.let { append(" · 预计 ").append(TimeFormat.listLine(it)) }
                append("）")
            }
            "- [ ] (${item.id.take(4)}) ${item.content}$stamp"
        }
    }

    /**
     * 注入 prompt 的待办摘要：未完成清单 + **最近完成的几条**（`- [x] …`）
     *
     * 「办完的事也看得见」——此前只注入未完成项，`todo_done` 一漏标（实盘：
     * 在群里看完就解析失败兜底退出，根本没机会输出），机器人被问「你不是去
     * 看过了吗」时仍一脸没办过的样子。完成项按 [TodoItem.completedAt] 倒序取
     * 最近 [doneLimit] 条（历史完成不删，面板仍见全量）。
     *
     * [doneLimit] 默认 2（原 5）：每行都随 prompt 重发，实盘样例 5 条里 4 条
     * 是同款换皮的「继续刚才的对话（被新通知打断）」——最近两条足够撑住
     * 「办过的事看得见」，更早的完成痕迹另有记忆留痕与操作日志兜底。
     */
    fun summaryForPrompt(doneLimit: Int = 2): String {
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

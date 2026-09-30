package org.dlucxoket.lucatbot.bot.todo

import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.slf4j.LoggerFactory

/**
 * 待办结算：**标完成 + 自动留痕**（三个完成路径统一收口）
 *
 * 实盘问题：机器人被托付「去XX群看看谁在找我」，去办了，但主人再发消息时它
 * 还是以为没办。根因有两截：
 * 1. 「办没办」只靠 LLM 在会话里自觉输出 `todo_done`——稍有岔子（如解析失败
 *    兜底退出）就漏标，待办永远挂着 `- [ ]`；
 * 2. 即便标了完成，prompt 此前只注入**未完成**待办，办完的事在别的会话里也
 *    没有记忆痕迹——跨会话被问「你不是去过了吗」时两头都看不见。
 *
 * 本组件把「完成」做成一条**账**：标完成 → 立刻写一条记忆「已办：内容」
 * （挂在待办来源会话的主体上，主人再聊时记忆摘要里直接可见）→ 记操作日志。
 * 调用方：会话内 `todo_done`（ConversationController）、消息列表 `todo_done`
 * （MessageListController）、退出时的兜底结算（MemoryReflector 的 done_todos）。
 *
 * 失败不影响主流程：匹配不到/写入异常一律只 log + 操作日志，返回 false。
 */
class TodoSettlement(
    private val todoService: TodoService,
    private val memoryService: MemoryService,
    private val actionLog: ActionLog
) {
    private val log = LoggerFactory.getLogger(TodoSettlement::class.java)

    /**
     * 按 id 前缀或内容片段结算一条待办（完成 + 留痕）
     *
     * @param ref LLM 给的引用（待办 id / id 前 4 位 / 内容片段）
     * @param conversationId 结算发生时所处会话（留痕日志用，可空——消息列表里
     *   结算就没有当前会话；记忆主体优先用待办自己的来源会话——任务是**在那个
     *   会话**被托付的，主人也在那儿问「办了吗」）
     * @return 是否结算成功
     */
    fun settle(ref: String, conversationId: String?): Boolean {
        val item = try {
            todoService.findPending(ref)
        } catch (e: Exception) {
            log.warn("待办结算查询失败: {}", e.message)
            null
        }
        if (item == null) {
            log.warn("todo_done 未匹配到待办: {}", ref)
            actionLog.append("待办未匹配：$ref", conversationId = conversationId)
            return false
        }
        return try {
            todoService.complete(item.id)
            actionLog.append("待办完成：${item.content}", conversationId = conversationId)
            // 留痕：完成事实写进**来源会话主体**的长期记忆（同内容精确判重，
            // 会话内已记过一次、退出结算再记一次也只留一条）
            val subjectConv = item.conversationId ?: conversationId
            if (!subjectConv.isNullOrBlank()) {
                memoryService.rememberEvent(
                    MemorySubject.fromConversation(subjectConv), "已办：${item.content}"
                )
            }
            log.info("待办结算（含留痕）: {} {}", item.id.take(4), item.content)
            true
        } catch (e: Exception) {
            log.warn("待办结算失败: {}", e.message)
            actionLog.append("待办完成失败", conversationId = conversationId)
            false
        }
    }
}

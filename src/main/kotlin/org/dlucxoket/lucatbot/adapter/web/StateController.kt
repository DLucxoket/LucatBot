package org.dlucxoket.lucatbot.adapter.web

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.todo.TodoItem
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 机器人状态总览 API——`web/` Vue 面板的另一半数据源
 *
 * 只读快照：心情、状态机当前态、待办（未完成/最近完成）、记忆（总量/按类型/最近几条）、
 * 操作日志、未读与最近会话。**不触发任何 LLM 调用**（读的都是内存/存储里的现成状态），
 * 所以面板可以随时刷、也不会烧 token。
 */
@RestController
@RequestMapping("/api/state")
class StateController(
    private val lucatBot: LucatBot,
    private val sessionManager: SessionManager,
    private val memoryService: MemoryService,
    private val todoService: TodoService,
    private val actionLog: ActionLog
) {

    data class MoodDto(val type: String, val intensity: Double, val text: String)

    data class TodoDto(
        val id: String,
        val content: String,
        val createdAt: Long,
        val completedAt: Long?,
        val conversationId: String?
    )

    data class MemoryDto(val content: String, val type: String, val createdAt: Long)

    data class ActionLineDto(
        val timestamp: Long,
        val conversationId: String?,
        val action: String,
        val reason: String?
    )

    data class StateDto(
        val mood: MoodDto,
        /** 状态机当前态（ViewingConversations / Idle / InConversation） */
        val state: String,
        val pendingTodos: List<TodoDto>,
        val doneTodos: List<TodoDto>,
        val memoryTotal: Int,
        val memoryByType: Map<String, Int>,
        val recentMemories: List<MemoryDto>,
        /** 按写入顺序（旧 → 新），前端倒序显示最新在上 */
        val actionLog: List<ActionLineDto>,
        /** 未读：会话键 → 未读条数 */
        val unread: Map<String, Int>,
        val recentConversations: List<String>,
        /** 服务端时间（epoch 毫秒）——前端据此算「多久之前」 */
        val serverTime: Long
    )

    @GetMapping("/overview")
    fun overview(): StateDto {
        val memories = memoryService.allMemories()
        val mood = lucatBot.mood
        return StateDto(
            mood = MoodDto(type = mood.type.name, intensity = mood.intensity, text = mood.toPrompt()),
            state = lucatBot.state::class.java.simpleName,
            pendingTodos = todoService.pending().map { it.toDto() },
            doneTodos = todoService.recentDone(8).map { it.toDto() },
            memoryTotal = memories.size,
            memoryByType = memories.groupingBy { it.type.name }.eachCount(),
            recentMemories = memories.sortedByDescending { it.createdAt }.take(12).map {
                MemoryDto(content = it.content, type = it.type.name, createdAt = it.createdAt)
            },
            actionLog = actionLog.all().map {
                ActionLineDto(timestamp = it.timestamp, conversationId = it.conversationId, action = it.action, reason = it.reason)
            },
            unread = lucatBot.getUnreadSummary(),
            recentConversations = sessionManager.recentConversationIds(),
            serverTime = System.currentTimeMillis()
        )
    }

    private fun TodoItem.toDto() = TodoDto(
        id = id, content = content, createdAt = createdAt, completedAt = completedAt, conversationId = conversationId
    )
}

package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.agent.data.ProactiveCandidate
import org.dlucxoket.lucatbot.bot.agent.data.SessionAction
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionSummaryLine
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.tool.ToolCatalog
import org.dlucxoket.lucatbot.bot.tool.ToolNotes
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.slf4j.LoggerFactory

/**
 * 消息列表编排：读列表 → 选会话/空闲
 *
 * 固定脚本调专用 Agent + Tool（SessionPickerAgent），无自由调度链路。
 * IO 经 Tool（processObject）；LLM JSON 只在 Agent 边界。
 *
 * 决策可能是多步的：智能体先 list_tools / tool（本类执行并把结果带进
 * 下一轮 prompt），再输出 open_conversation / idle 结束本轮。
 */
class MessageListController(
    private val lucatBot: LucatBot,
    private val sessionManager: SessionManager,
    private val memoryService: MemoryService,
    private val chatHistoryService: ChatHistoryService,
    private val todoService: TodoService,
    private val actionLog: ActionLog,
    private val sessionPickerAgent: SessionPickerAgent = SessionPickerAgent(),
    /** 工具目录与调用网关（list_tools / tool 动作的执行层） */
    private val toolCatalog: ToolCatalog = ToolCatalog()
) {
    private val log = LoggerFactory.getLogger(MessageListController::class.java)

    /**
     * 走一轮「看消息列表」决策（可含多步工具调用）
     *
     * @param fromIdleWake 是否是空闲唤醒（拼入可主动找的人）
     * @return 选中的会话；null = 去空闲
     */
    fun pickSession(fromIdleWake: Boolean): SessionPick? {
        val sessions = collectSessions()
        actionLog.append("查看消息列表（${sessions.sumOf { it.unreadCount }}条未读）")

        val candidates = if (fromIdleWake) collectProactiveCandidates() else emptyList()
        // 工具上下文（本轮内累积：状态计数/目录/执行结果；选会话侧硬上限更紧）
        val toolNotes = ToolNotes(maxTotalChars = 16_000, hardLimitSteps = 6)

        while (true) {
            val request = SessionPickerRequest(
                persona = lucatBot.persona,
                mood = lucatBot.mood,
                timeHint = TimeAwareness.timeHint(),
                shortTermMemory = buildShortTermMemory(sessions),
                todos = todoService.pendingSummary(),
                actionLog = actionLog.dump(),
                sessions = sessions,
                proactiveCandidates = candidates,
                toolNotes = toolNotes.snapshot()
            )

            val decision = sessionPickerAgent.processObject(request)

            // 防病态值资源护栏：连续工具步达硬上限 → 兜底去空闲（正常场景
            // 一两步工具就该出结论，6 步仍不决策属病态路径）
            val isToolStep = decision.action == SessionAction.LIST_TOOLS ||
                decision.action == SessionAction.CALL_TOOL
            if (isToolStep && toolNotes.exceeded()) {
                actionLog.append("连续工具步超限，去空闲")
                log.warn("选会话连续工具步超限（{} 步），兜底 idle", toolNotes.steps)
                return null
            }
            if (!isToolStep) toolNotes.resetStreak()

            when (decision.action) {
                SessionAction.OPEN_CONVERSATION -> {
                    val pick = decision.pick ?: return null  // parse 已保证非空，此处仅防御
                    log.info("选会话结果: {}", pick)
                    return pick
                }

                SessionAction.IDLE -> {
                    log.info("选会话结果: idle")
                    return null
                }

                SessionAction.LIST_TOOLS -> {
                    val query = decision.query
                    toolNotes.recordCatalog(query, toolCatalog.catalog(query))
                    actionLog.append("查看工具目录（${toolCatalog.count(query)} 个）")
                    log.info("list_tools query={} → {} 个", query ?: "*", toolCatalog.count(query))
                }

                SessionAction.CALL_TOOL -> {
                    val toolName = decision.toolName.orEmpty()
                    val result = toolCatalog.invoke(toolName, decision.toolInput)
                    toolNotes.recordResult(toolName, result)
                    actionLog.append("调用工具 $toolName")
                    log.info("tool {} → {} 字符", toolName, result.length)
                }
            }
        }
    }

    /** 汇总未读/最近会话（含已读与未读） */
    private fun collectSessions(): List<SessionSummaryLine> {
        val unread = lucatBot.getUnreadSummary()
        val lines = mutableListOf<SessionSummaryLine>()

        for ((convId, count) in unread) {
            val msgs = lucatBot.peekUnread(convId)
            val last = msgs.lastOrNull()
            val isGroup = convId.startsWith("group:")
            val id = convId.substringAfter(":")
            val name = last?.senderName?.ifBlank { id } ?: id
            lines += SessionSummaryLine(
                conversationType = if (isGroup) "群聊" else "私聊",
                name = name,
                id = id,
                time = formatTime(last?.timestamp ?: 0L),
                unreadCount = count,
                lastMessage = last?.content.orEmpty(),
                isAtMe = lucatBot.hasUnreadAtMe(convId),
                isAtAll = lucatBot.hasUnreadAtAll(convId)
            )
        }

        // 已读但最近活跃的会话（无未读也展示，模拟「看过列表」）
        val recent = sessionManager.recentConversationIds(withinMinutes = 24 * 60)
            .filter { it !in unread.keys }
        for (convId in recent.take(5)) {
            val entry = sessionManager.getLastMessage(convId) ?: continue
            val isGroup = convId.startsWith("group:")
            lines += SessionSummaryLine(
                conversationType = if (isGroup) "群聊" else "私聊",
                name = entry.sender.ifBlank { convId.substringAfter(":") },
                id = convId.substringAfter(":"),
                time = formatTime(entry.timestamp),
                unreadCount = 0,
                lastMessage = entry.content
            )
        }
        return lines
    }

    /** 可主动找的人：最近 24h 聊过的私聊对象，最多 3 个；心情很差/很困时少拼或不拼 */
    private fun collectProactiveCandidates(): List<ProactiveCandidate> {
        val mood = lucatBot.mood
        val cap = when (mood.type) {
            BotMood.MoodType.SLEEPY, BotMood.MoodType.TIRED -> 1
            BotMood.MoodType.ANNOYED -> if (mood.intensity > 0.7) 0 else 1
            else -> 3
        }
        if (cap == 0) return emptyList()

        return sessionManager.recentConversationIds(withinMinutes = 24 * 60)
            .filter { it.startsWith("private:") }
            .take(cap)
            .map { convId ->
                val subject = MemorySubject.fromConversation(convId)
                val memory = memoryService.getMemorySummary(subject).lineSequence().firstOrNull().orEmpty()
                val hint = when {
                    memory.isNotBlank() -> memory
                    sessionManager.isActive(convId, withinMinutes = 60) -> "刚才还在聊"
                    else -> "这个点他可能在"
                }
                ProactiveCandidate(
                    name = sessionManager.getLastMessage(convId)?.sender ?: convId.substringAfter(":"),
                    id = convId.substringAfter(":"),
                    hint = hint
                )
            }
    }

    private fun buildShortTermMemory(sessions: List<SessionSummaryLine>): String {
        val parts = mutableListOf<String>()
        for (s in sessions.take(3)) {
            val userId = s.id.toLongOrNull() ?: continue
            // 记忆按主体隔离：群聊会话取群记忆，私聊取个人记忆
            val subject = if (s.conversationType == "群聊") {
                MemorySubject.group(userId)
            } else {
                MemorySubject.user(userId)
            }
            val m = memoryService.getMemorySummary(subject)
            if (m.isNotBlank()) parts += m
        }
        return parts.joinToString("\n")
    }

    private fun formatTime(ts: Long): String {
        if (ts <= 0L) return ""
        val t = java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.of("Asia/Shanghai"))
            .toLocalDateTime()
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        return if (t.toLocalDate() == today) {
            t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        } else {
            t.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        }
    }
}

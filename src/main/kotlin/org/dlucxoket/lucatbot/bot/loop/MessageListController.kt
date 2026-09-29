package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.agent.data.ProactiveCandidate
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionSummaryLine
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.slf4j.LoggerFactory

/**
 * 消息列表编排：读列表 → 选会话/空闲
 *
 * 固定脚本调专用 Agent + Tool（SessionPickerAgent），无自由调度链路。
 * IO 经 Tool（processObject）；LLM JSON 只在 Agent 边界。
 */
class MessageListController(
    private val lucatBot: LucatBot,
    private val sessionManager: SessionManager,
    private val memoryService: MemoryService,
    private val chatHistoryService: ChatHistoryService,
    private val todoService: TodoService,
    private val actionLog: ActionLog,
    private val sessionPickerAgent: SessionPickerAgent = SessionPickerAgent()
) {
    private val log = LoggerFactory.getLogger(MessageListController::class.java)

    /**
     * 走一轮「看消息列表」决策
     *
     * @param fromIdleWake 是否是空闲唤醒（拼入可主动找的人）
     * @return 选中的会话；null = 去空闲
     */
    fun pickSession(fromIdleWake: Boolean): SessionPick? {
        val sessions = collectSessions()
        actionLog.append("查看消息列表（${sessions.sumOf { it.unreadCount }}条未读）")

        val candidates = if (fromIdleWake) collectProactiveCandidates() else emptyList()

        val request = SessionPickerRequest(
            persona = lucatBot.persona,
            mood = lucatBot.mood,
            timeHint = TimeAwareness.timeHint(),
            shortTermMemory = buildShortTermMemory(sessions),
            todos = todoService.pendingSummary(),
            actionLog = actionLog.dump(),
            sessions = sessions,
            proactiveCandidates = candidates
        )

        val pick = sessionPickerAgent.processObject(request)
        log.info("选会话结果: {}", pick ?: "idle")
        return pick
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

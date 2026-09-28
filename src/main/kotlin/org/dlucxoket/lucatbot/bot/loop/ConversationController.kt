package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationAction
import org.dlucxoket.lucatbot.bot.agent.data.ConversationChatLine
import org.dlucxoket.lucatbot.bot.agent.data.ConversationDecision
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryExtractor
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.tool.SendMessageRequest
import org.dlucxoket.lucatbot.bot.tool.SendMessageResult
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.slf4j.LoggerFactory

/**
 * 会话内编排循环：读历史 → 决策 → read_more / send / return
 *
 * 决策智能体只输出动作；IO 一律走 Tool 的 processObject。
 * 含 read_more 上限、新消息待插入队列、强制 return 条件。
 */
class ConversationController(
    private val lucatBot: LucatBot,
    private val sessionManager: SessionManager,
    private val memoryService: MemoryService,
    private val chatHistoryService: ChatHistoryService,
    private val actionLog: ActionLog,
    private val conversationAgent: ConversationAgent = ConversationAgent(),
    private val chatHistoryTool: Tool<ChatHistoryRequest, ChatHistoryResult>,
    private val sendMessageTool: Tool<SendMessageRequest, SendMessageResult>,
    /** 初始读史条数 */
    private val initialHistoryCount: Int = 10,
    /** 连续 read_more 上限 */
    private val maxReadMore: Int = 5,
    /** 连续无新消息且无 send 的强制离开轮数 */
    private val maxStaleRounds: Int = 6,
    /** 连续 read_more 且一直有新消息插入的打断轮数 */
    private val maxReadMoreWithInserts: Int = 3,
    /** 发出消息后等待对方回话的超时（毫秒）；超时无人接话则离开会话，避免连发刷屏 */
    private val replyWaitMs: Long = 45_000L
) {
    private val log = LoggerFactory.getLogger(ConversationController::class.java)

    /** 本会话内决策循环的结果 */
    enum class ExitReason {
        DECISION_RETURN,
        READ_MORE_LIMIT,
        STALE_NO_SEND,
        MOOD_EXHAUSTED,
        SEND_FAILURE
    }

    /**
     * 跑完整的会话内循环，直到离开
     */
    fun run(pick: SessionPick) {
        val conversationId = toConversationId(pick)
        val isGroup = pick.conversationType == SessionPickType.GROUP
        val proactive = pick.conversationType == SessionPickType.PROACTIVE

        lucatBot.enterConversation(
            conversationId,
            if (isGroup) LucatBot.ConversationType.GROUP else LucatBot.ConversationType.PRIVATE
        )
        lucatBot.mood = lucatBot.mood.boost()
        actionLog.append(
            "进入${if (isGroup) "群聊" else "私聊"} ${pick.targetId}" + if (proactive) "（主动找人）" else ""
        )

        // 进入会话即消费未读并标已读
        lucatBot.consumeUnread(conversationId)
        chatHistoryService.markAsRead(conversationId)

        try {
            loop(conversationId, isGroup, proactive)
        } finally {
            actionLog.append("返回消息列表")
            lucatBot.backToConversationList()
        }
    }

    private fun loop(conversationId: String, isGroup: Boolean, proactive: Boolean) {
        var historyCount = initialHistoryCount
        var readMoreStreak = 0
        var staleRounds = 0
        var readMoreWithInsertStreak = 0
        var mustIcebreak = proactive

        while (true) {
            // 1) 把待插入的新消息以系统注释形式合并
            val inserts = lucatBot.drainPendingInserts(conversationId)
            val newNotes = inserts.map {
                "（新消息）${it.senderName}(${it.senderId}) [${formatTime(it.timestamp)}]：${it.content}"
            }

            // 2) 读历史（Tool → OOP）
            val historyResult = chatHistoryTool.processObject(
                ChatHistoryRequest(
                    targetId = conversationId.substringAfter(":").toLongOrNull() ?: 0L,
                    count = historyCount,
                    type = if (isGroup) "group" else "private"
                )
            )
            val recentLines = historyResult.messages.map {
                ConversationChatLine(
                    sender = it.sender,
                    senderId = "",
                    isMe = it.sender == "bot" || it.sender == "我",
                    time = formatTime(it.timestamp),
                    content = it.content
                )
            }

            // 3) 组请求并决策
            val request = ConversationRequest(
                persona = lucatBot.persona,
                mood = lucatBot.mood,
                timeHint = TimeAwareness.timeHint(),
                conversationType = if (isGroup) "群聊" else "私聊",
                conversationName = pickName(conversationId),
                conversationId = conversationId,
                shortTermMemory = memoryService.getMemorySummary(
                    conversationId.substringAfter(":").toLongOrNull() ?: 0L
                ) + sessionManager.getFatigueHint(conversationId),
                actionLog = actionLog.dump(),
                recentMessages = recentLines,
                newMessageNotes = newNotes,
                proactive = mustIcebreak,
                hint = if (readMoreWithInsertStreak >= maxReadMoreWithInserts) {
                    "有人在等你回应，优先处理新消息（send 或 return），不要再爬楼。"
                } else ""
            )

            var decision = conversationAgent.processObject(request)

            // proactive 破冰：首条必须 send
            if (mustIcebreak && decision.action != ConversationAction.SEND) {
                decision = ConversationDecision(
                    action = ConversationAction.SEND,
                    params = mapOf("content" to "在吗"),
                    reason = "主动破冰（强制）"
                )
            }
            // 连续 read_more 且有新消息 → 打断，强制重新决策（不许再爬楼）
            if (readMoreWithInsertStreak >= maxReadMoreWithInserts &&
                decision.action == ConversationAction.READ_MORE
            ) {
                decision = ConversationDecision.forceReturn("连续爬楼期间一直有新消息，强制回应或离开")
            }
            // 连续 read_more 超限 → 强制 send 或 return
            if (readMoreStreak >= maxReadMore && decision.action == ConversationAction.READ_MORE) {
                decision = ConversationDecision.forceReturn("连续爬楼超过上限")
            }

            val hadInserts = newNotes.isNotEmpty()

            when (decision.action) {
                ConversationAction.READ_MORE -> {
                    val count = decision.readCount
                    historyCount += count
                    readMoreStreak++
                    if (hadInserts) readMoreWithInsertStreak++ else readMoreWithInsertStreak = 0
                    if (!hadInserts) {
                        staleRounds++
                        lucatBot.mood = lucatBot.mood.decay()
                    }
                    actionLog.append("爬楼 $count 条")
                    log.info("read_more → 累计读取 {}", historyCount)
                }

                ConversationAction.SEND -> {
                    val content = decision.content.orEmpty()
                    val sendResult = sendMessageTool.processObject(
                        SendMessageRequest(conversationId = conversationId, content = content)
                    )
                    if (!sendResult.success) {
                        log.warn("发送失败: {}", sendResult.error)
                        actionLog.append("发送失败")
                        // 发不出去就离开，避免原地空转
                        lucatBot.backToConversationList()
                        return
                    }
                    // 发出后：SendMessageTool 已写聊天记录（isBot=true）；这里补短期记忆
                    sessionManager.addMessage(
                        conversationId, lucatBot.persona.name, content,
                        isBot = true
                    )
                    memoryService.saveAll(
                        MemoryExtractor.extract(
                            conversationId.substringAfter(":").toLongOrNull() ?: 0L,
                            recentLines.lastOrNull()?.content.orEmpty(),
                            content
                        )
                    )
                    actionLog.append("回复：$content")
                    lucatBot.mood = lucatBot.mood.boost()

                    mustIcebreak = false
                    readMoreStreak = 0
                    staleRounds = 0
                    readMoreWithInsertStreak = 0

                    // 发完必须等对方接话，禁止立刻再决策连发（刷屏 bug）
                    if (!waitForNewMessages(conversationId)) {
                        actionLog.append("等待回应超时，结束会话")
                        log.info("send 后 {}ms 内无人回应，离开会话", replyWaitMs)
                        return
                    }
                }

                ConversationAction.RETURN -> {
                    log.info("决策 return，离开会话")
                    return
                }
            }

            // 何时离开：连续 6 轮没有任何新消息且决策给不出 send
            if (staleRounds >= maxStaleRounds) {
                log.info("连续 {} 轮无新消息且无 send，强制离开", staleRounds)
                return
            }
            // 空闲类情绪拉满 → 强制 return
            if (isMoodExhausted()) {
                log.info("空闲类情绪拉满，强制离开")
                return
            }
        }
    }

    /**
     * 发出消息后等待对方新消息
     *
     * @return true 表示有人接话，可以继续决策；false 超时应离开会话
     */
    private fun waitForNewMessages(conversationId: String): Boolean {
        if (lucatBot.hasPendingInserts(conversationId)) return true
        if (replyWaitMs <= 0L) return false

        val deadline = System.currentTimeMillis() + replyWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (lucatBot.hasPendingInserts(conversationId)) return true
            try {
                Thread.sleep(200L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return lucatBot.hasPendingInserts(conversationId)
    }

    /** 空闲类情绪（累/困/烦）拉满 */
    private fun isMoodExhausted(): Boolean {
        val m = lucatBot.mood
        val idleType = m.type == BotMood.MoodType.TIRED ||
            m.type == BotMood.MoodType.SLEEPY ||
            m.type == BotMood.MoodType.ANNOYED
        return idleType && m.intensity >= 0.9
    }

    private fun toConversationId(pick: SessionPick): String {
        return when (pick.conversationType) {
            SessionPickType.GROUP -> "group:${pick.targetId}"
            SessionPickType.PRIVATE, SessionPickType.PROACTIVE -> "private:${pick.targetId}"
        }
    }

    private fun pickName(conversationId: String): String {
        return sessionManager.getLastMessage(conversationId)?.sender
            ?: conversationId.substringAfter(":")
    }

    private fun formatTime(ts: Long): String {
        if (ts <= 0L) return ""
        return java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.of("Asia/Shanghai"))
            .toLocalDateTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
}

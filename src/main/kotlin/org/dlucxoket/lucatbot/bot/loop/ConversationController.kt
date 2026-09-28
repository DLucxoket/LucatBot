package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationAction
import org.dlucxoket.lucatbot.bot.agent.data.ConversationChatLine
import org.dlucxoket.lucatbot.bot.agent.data.ConversationDecision
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryExtractor
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.tool.SendMessageRequest
import org.dlucxoket.lucatbot.bot.tool.SendMessageResult
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.slf4j.LoggerFactory

/**
 * 会话内编排循环：读历史 → 决策 → read_more / send / stay / return
 *
 * 决策智能体只输出动作；IO 一律走 Tool 的 processObject。
 * read_more 无次数限制，prompt 里给出爬楼状态（次数/覆盖量/是否翻到头）由 LLM 自行决定去留；
 * 代码侧仅保留两条打断：连续爬楼期间有新消息、发送失败、情绪拉满。
 * send 成功后**立即**进入下一轮决策（无阻塞等待），连发由 prompt 的 hint 约束；
 * stay 由 LLM 自主决定停留秒数（钳制 1~1800s），期间新消息照常进待插入队列。
 * loop() 正常返回后（finally 之前）调用一次 [MemoryAgent] 做退出时的记忆整理，
 * 整理失败只 log，不影响返回消息列表。
 */
class ConversationController(
    private val lucatBot: LucatBot,
    private val sessionManager: SessionManager,
    private val memoryService: MemoryService,
    private val chatHistoryService: ChatHistoryService,
    private val todoService: TodoService,
    private val actionLog: ActionLog,
    private val conversationAgent: ConversationAgent = ConversationAgent(),
    private val memoryAgent: MemoryAgent = MemoryAgent(),
    private val chatHistoryTool: Tool<ChatHistoryRequest, ChatHistoryResult>,
    private val sendMessageTool: Tool<SendMessageRequest, SendMessageResult>,
    /** 初始读史条数 */
    private val initialHistoryCount: Int = 10,
    /** 退出会话时记忆整理读取的聊天记录条数 */
    private val reflectHistoryCount: Int = 30,
    /** 连续 read_more 且一直有新消息插入的打断轮数 */
    private val maxReadMoreWithInserts: Int = 3
) {
    private val log = LoggerFactory.getLogger(ConversationController::class.java)

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
            // loop() 正常返回（含内部 return）→ 退出前整理一次记忆；
            // loop() 抛异常则跳过整理，直接走 finally
            reflectMemory(conversationId)
        } finally {
            actionLog.append("返回消息列表")
            lucatBot.backToConversationList()
        }
    }

    private fun loop(conversationId: String, isGroup: Boolean, proactive: Boolean) {
        var historyCount = initialHistoryCount
        var readMoreRounds = 0
        var readMoreWithInsertStreak = 0
        var mustIcebreak = proactive
        var justSent: String? = null // 本轮刚发出的消息（有新回应即清空，用于组装 hint）

        while (true) {
            // 1) 把待插入的新消息以系统注释形式合并
            val inserts = lucatBot.drainPendingInserts(conversationId)
            if (inserts.isNotEmpty()) justSent = null // 有新回应就不再提示旧发送
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

            // 3) 组请求并决策（带爬楼状态，由 LLM 自行决定继续/回复/离开）
            val reachedHistoryStart = historyResult.messages.size < historyCount
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
                todos = todoService.pendingSummary(),
                actionLog = actionLog.dump(),
                recentMessages = recentLines,
                newMessageNotes = newNotes,
                proactive = mustIcebreak,
                readMoreRounds = readMoreRounds,
                historyCovered = historyCount,
                reachedHistoryStart = reachedHistoryStart,
                hint = buildHint(readMoreWithInsertStreak, justSent, newNotes)
            )

            var decision = conversationAgent.processObject(request)
            applyDecisionSideEffects(decision, conversationId)

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

            val hadInserts = newNotes.isNotEmpty()

            when (decision.action) {
                ConversationAction.READ_MORE -> {
                    val count = decision.readCount
                    historyCount += count
                    readMoreRounds++
                    if (hadInserts) readMoreWithInsertStreak++ else readMoreWithInsertStreak = 0
                    if (!hadInserts) {
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
                            recentLines.lastOrNull()?.content.orEmpty()
                        )
                    )
                    actionLog.append("回复：$content")
                    lucatBot.mood = lucatBot.mood.boost()

                    mustIcebreak = false
                    readMoreWithInsertStreak = 0
                    // 发完不等待，立即进入下一轮决策；连发由 hint 约束
                    justSent = content
                }

                ConversationAction.STAY -> {
                    // 上限防病态值（非退出限制）；prompt 建议 LLM 通常给 10~120 秒
                    val sec = decision.staySeconds.coerceIn(1, 1800)
                    try {
                        Thread.sleep(sec * 1000L)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        log.info("停留 {}s 被中断，退出会话", sec)
                        return
                    }
                    actionLog.append("停留 ${sec}s")
                    readMoreWithInsertStreak = 0 // 停留打断连续爬楼计数
                }

                ConversationAction.RETURN -> {
                    log.info("决策 return，离开会话")
                    return
                }
            }

            // 空闲类情绪拉满 → 强制 return
            if (isMoodExhausted()) {
                log.info("空闲类情绪拉满，强制离开")
                return
            }
        }
    }

    /**
     * 退出会话时的记忆整理：把会话内容交给 [MemoryAgent] 判断有没有值得长期记住的信息，
     * 按 kind 写入 [MemoryService]（fact → rememberFact / opinion → rememberOpinion / event → rememberEvent）。
     *
     * 整个过程 try/catch：失败只 log.warn，绝不影响后续 actionLog / backToConversationList。
     */
    private fun reflectMemory(conversationId: String) {
        try {
            val userId = conversationId.substringAfter(":").toLongOrNull() ?: 0L
            val result = memoryAgent.processObject(
                MemoryReflectRequest(
                    conversationContent = chatHistoryService.getHistorySummary(
                        conversationId, reflectHistoryCount
                    ),
                    existingMemory = memoryService.getMemorySummary(userId),
                    timeHint = TimeAwareness.timeHint()
                )
            )
            if (result.memories.isEmpty()) {
                log.debug("退出时记忆整理：无值得记录的内容")
                return
            }
            var written = 0
            for (item in result.memories) {
                when (item.kind.lowercase()) {
                    "fact" -> {
                        memoryService.rememberFact(userId, item.content, item.tags)
                        written++
                    }
                    "opinion" -> {
                        memoryService.rememberOpinion(userId, item.content)
                        written++
                    }
                    "event" -> {
                        memoryService.rememberEvent(userId, item.content)
                        written++
                    }
                    else -> log.debug("退出时记忆整理：跳过未知 kind={}（content={}）", item.kind, item.content)
                }
            }
            log.info("退出时记忆整理：写入 {} 条", written)
            if (written > 0) {
                actionLog.append("整理记忆 $written 条")
            }
        } catch (e: Exception) {
            log.warn("退出时记忆整理失败（忽略，不影响返回消息列表）: {}", e.message)
        }
    }

    /**
     * 处理决策信封的副作用字段（在执行 action 之前）
     *
     * remember → 短期记忆；todo_add / todo_done → 待办清单。
     * 每项单独 try/catch：失败只 log，不中断主循环。
     */
    private fun applyDecisionSideEffects(decision: ConversationDecision, conversationId: String) {
        decision.remember?.let { note ->
            try {
                memoryService.rememberEvent(
                    conversationId.substringAfter(":").toLongOrNull() ?: 0L,
                    note
                )
            } catch (e: Exception) {
                log.warn("remember 写入失败: {}", e.message)
            }
        }
        decision.todo_add?.let { content ->
            try {
                todoService.add(content, conversationId)
            } catch (e: Exception) {
                log.warn("todo_add 写入失败: {}", e.message)
            }
        }
        decision.todo_done?.let { ref ->
            try {
                if (!todoService.complete(ref)) {
                    log.warn("todo_done 未匹配到待办: {}", ref)
                }
            } catch (e: Exception) {
                log.warn("todo_done 处理失败: {}", e.message)
            }
        }
    }

    /**
     * 组装决策 prompt 的强制约束提示（可多段，换行连接）
     *
     * - 连续爬楼期间一直有新消息：打断爬楼，优先回应
     * - 刚 send 完且暂无新回应：提示不要连发（stay / return / 确有必要再发）
     */
    private fun buildHint(
        readMoreWithInsertStreak: Int,
        justSent: String?,
        newNotes: List<String>
    ): String {
        val parts = mutableListOf<String>()
        if (readMoreWithInsertStreak >= maxReadMoreWithInserts) {
            parts += "有人在等你回应，优先处理新消息（send 或 return），不要再爬楼。"
        }
        if (justSent != null && newNotes.isEmpty()) {
            parts += "你刚发送了「$justSent」，暂无新回应。不要立刻连发；可用 stay 等待一会儿、return 离开，或确实有必要再发。"
        }
        return parts.joinToString("\n")
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

    /** 今天 → HH:mm；非今天 → yyyy-MM-dd HH:mm（让 LLM 能判断消息新旧） */
    private fun formatTime(ts: Long): String {
        if (ts <= 0L) return ""
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val dateTime = java.time.Instant.ofEpochMilli(ts)
            .atZone(zone)
            .toLocalDateTime()
        val pattern =
            if (dateTime.toLocalDate() == java.time.LocalDate.now(zone)) "HH:mm"
            else "yyyy-MM-dd HH:mm"
        return dateTime.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    }
}

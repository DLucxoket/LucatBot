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
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.tool.SendMessageRequest
import org.dlucxoket.lucatbot.bot.tool.SendMessageResult
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.dlucxoket.lucatbot.bot.tool.ToolCatalog
import org.dlucxoket.lucatbot.bot.tool.ToolNotes
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.slf4j.LoggerFactory

/**
 * 会话内编排循环：读历史 → 决策 → read_more / send / stay / return
 *
 * 决策智能体只输出动作；IO 一律走 Tool 的 processObject。
 * read_more 无次数限制，prompt 里给出爬楼状态（次数/覆盖量/是否翻到头）由 LLM 自行决定去留；
 * 代码侧仅保留两条打断：连续爬楼期间有新消息、发送失败。
 * send 成功后**立即**进入下一轮决策（无阻塞等待），连发由 prompt 的 hint 约束
 * （hint 携带「连续无新消息轮数」递进变量，交由 LLM 判断，无代码强制退出）；
 * stay 由 LLM 自主决定停留秒数（钳制 1~1800s），期间新消息照常进待插入队列；
 * 计时未到但当前会话来了新消息 → 提前唤醒（取消剩余计时），否则等满秒数。
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
    /** 工具目录与调用网关（list_tools / tool 动作的执行层） */
    private val toolCatalog: ToolCatalog = ToolCatalog(),
    /** 初始读史条数 */
    private val initialHistoryCount: Int = 10,
    /** 退出会话时记忆整理读取的聊天记录条数 */
    private val reflectHistoryCount: Int = 30,
    /** 连续 read_more 且一直有新消息插入的打断轮数 */
    private val maxReadMoreWithInserts: Int = 3
) {
    private val log = LoggerFactory.getLogger(ConversationController::class.java)

    /** 退出记忆整理的幂等保护：正常退出与停机收尾对同一会话只整理一次 */
    private val reflectLock = Any()

    @Volatile
    private var lastReflectedConversation: String? = null

    /**
     * 跑完整的会话内循环，直到离开
     */
    fun run(pick: SessionPick) {
        val conversationId = toConversationId(pick)
        val isGroup = pick.conversationType == SessionPickType.GROUP
        val proactive = pick.conversationType == SessionPickType.PROACTIVE

        // 重置幂等保护：同一会话再次进入时允许重新整理
        lastReflectedConversation = null

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
        var noReplyRounds = 0 // 连续无新消息的轮数（自上次发送起累计；SEND 成功不重置，有新消息清零）
        // 工具上下文（本会话内累积：状态计数/目录/执行结果，随每轮注入 prompt）
        val toolNotes = ToolNotes(maxTotalChars = 16_000, hardLimitSteps = 12)

        while (true) {
            // 1) 把待插入的新消息以系统注释形式合并
            val inserts = lucatBot.drainPendingInserts(conversationId)
            if (inserts.isNotEmpty()) {
                justSent = null // 有新回应就不再提示旧发送
                noReplyRounds = 0
            } else if (justSent != null) {
                // 已发出消息且本轮仍没有任何新消息 → 递进一轮（衡量连续无回应）
                noReplyRounds++
            }
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
                    MemorySubject.fromConversation(conversationId)
                ) + sessionManager.getFatigueHint(conversationId),
                todos = todoService.pendingSummary(),
                actionLog = actionLog.dump(),
                recentMessages = recentLines,
                newMessageNotes = newNotes,
                proactive = mustIcebreak,
                readMoreRounds = readMoreRounds,
                historyCovered = historyCount,
                reachedHistoryStart = reachedHistoryStart,
                hint = buildHint(readMoreWithInsertStreak, justSent, noReplyRounds, newNotes),
                toolNotes = toolNotes.snapshot()
            )

            var decision = conversationAgent.processObject(request)
            applyDecisionSideEffects(decision, conversationId)

            // proactive 破冰：首条必须 send（查目录/调工具是前置动作，不视为破冰失败）
            val isToolStep = decision.action == ConversationAction.LIST_TOOLS ||
                decision.action == ConversationAction.CALL_TOOL
            if (mustIcebreak && decision.action != ConversationAction.SEND && !isToolStep) {
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

            // 防病态值资源护栏：连续工具步达硬上限 → 强制收尾（与 stay 钳制同性质，
            // 不限制正常工具使用，只兜底「无限烧 token」的病态路径）
            if (isToolStep && toolNotes.exceeded()) {
                actionLog.append("连续工具步超限，返回消息列表")
                decision = ConversationDecision.forceReturn("连续工具步已达上限，先结束本轮工具使用")
            }
            // 非工具决策（含被强制的 return/send）→ 重置连续工具步计数
            if (decision.action != ConversationAction.LIST_TOOLS &&
                decision.action != ConversationAction.CALL_TOOL
            ) {
                toolNotes.resetStreak()
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
                    // 只从「对方最后一条消息」提取记忆：recentLines 末尾可能是
                    // 机器人自己的回复，把 bot 的话喂给正则会误记成用户的长期 FACT
                    val lastUserContent = recentLines.lastOrNull { !it.isMe }?.content.orEmpty()
                    if (lastUserContent.isNotBlank()) {
                        memoryService.saveAll(
                            MemoryExtractor.extract(
                                MemorySubject.fromConversation(conversationId),
                                lastUserContent
                            )
                        )
                    }
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
                    // 计时等待：当前会话在时间到之前来了新消息 → 立即唤醒（取消剩余计时），否则等满
                    val interrupted = try {
                        val wokenByMessage = lucatBot.awaitPendingInsert(conversationId, sec * 1000L)
                        if (wokenByMessage) {
                            log.info("停留计划 {}s，被新消息提前唤醒", sec)
                            actionLog.append("停留 ${sec}s（新消息提前唤醒）")
                        } else {
                            actionLog.append("停留 ${sec}s")
                        }
                        false
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        log.info("停留 {}s 被中断，退出会话", sec)
                        true
                    }
                    if (interrupted) return
                    readMoreWithInsertStreak = 0 // 停留打断连续爬楼计数
                }

                ConversationAction.RETURN -> {
                    log.info("决策 return，离开会话")
                    return
                }

                ConversationAction.LIST_TOOLS -> {
                    val query = decision.toolQuery
                    toolNotes.recordCatalog(query, toolCatalog.catalog(query))
                    actionLog.append("查看工具目录（${toolCatalog.count(query)} 个）")
                    log.info("list_tools query={} → {} 个", query ?: "*", toolCatalog.count(query))
                }

                ConversationAction.CALL_TOOL -> {
                    val toolName = decision.toolName.orEmpty()
                    val result = toolCatalog.invoke(toolName, decision.toolInput)
                    toolNotes.recordResult(toolName, result)
                    actionLog.append("调用工具 $toolName")
                    log.info("tool {} → {} 字符", toolName, result.length)
                }
            }
        }
    }

    /**
     * 停机收尾入口：程序停止时若停在某个会话里，补做一次退出记忆整理。
     *
     * 幂等：[reflectMemory] 内有同会话去重保护，正常退出路径已整理过则
     * 直接跳过，不会重复写入；不在会话中时为空操作。
     */
    fun reflectForShutdown() {
        val state = lucatBot.state
        if (state is LucatBot.State.InConversation) {
            log.info("停机收尾：会话 {} 尚未整理记忆，先补一次", state.conversationId)
            reflectMemory(state.conversationId)
        }
    }

    /**
     * 退出会话时的记忆整理：把会话内容交给 [MemoryAgent] 判断有没有值得长期记住的信息，
     * 按 kind 写入 [MemoryService]（fact → rememberFact / opinion → rememberOpinion / event → rememberEvent）。
     *
     * 整个过程 try/catch：失败只 log.warn，绝不影响后续 actionLog / backToConversationList。
     *
     * 同会话幂等（[reflectLock] 保护）：停机收尾与正常退出可能对同一会话
     * 各触发一次，只整理第一遍，避免重复写入。
     */
    private fun reflectMemory(conversationId: String) {
        synchronized(reflectLock) {
            if (lastReflectedConversation == conversationId) {
                log.debug("会话 {} 的记忆已整理过，跳过重复整理", conversationId)
                return
            }
            doReflectMemory(conversationId)
            lastReflectedConversation = conversationId
        }
    }

    /** 记忆整理的实际执行（仅在 [reflectLock] 内调用） */
    private fun doReflectMemory(conversationId: String) {
        try {
            val subject = MemorySubject.fromConversation(conversationId)
            val result = memoryAgent.processObject(
                MemoryReflectRequest(
                    conversationContent = chatHistoryService.getHistorySummary(
                        conversationId, reflectHistoryCount
                    ),
                    existingMemory = memoryService.getMemorySummary(subject),
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
                        memoryService.rememberFact(subject, item.content, item.tags)
                        written++
                    }
                    "opinion" -> {
                        memoryService.rememberOpinion(subject, item.content)
                        written++
                    }
                    "event" -> {
                        memoryService.rememberEvent(subject, item.content)
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
     * 每项单独 try/catch：失败只 log，不中断主循环；
     * 成功与失败都追加一行操作日志（记住 / 待办+ / 待办完成 / 写入失败）。
     */
    private fun applyDecisionSideEffects(decision: ConversationDecision, conversationId: String) {
        decision.remember?.let { note ->
            try {
                memoryService.rememberEvent(
                    MemorySubject.fromConversation(conversationId),
                    note
                )
                actionLog.append("记住：$note")
            } catch (e: Exception) {
                log.warn("remember 写入失败: {}", e.message)
                actionLog.append("记忆写入失败")
            }
        }
        decision.todo_add?.let { content ->
            try {
                todoService.add(content, conversationId)
                actionLog.append("待办+：$content")
            } catch (e: Exception) {
                log.warn("todo_add 写入失败: {}", e.message)
                actionLog.append("待办写入失败")
            }
        }
        decision.todo_done?.let { ref ->
            try {
                if (todoService.complete(ref)) {
                    actionLog.append("待办完成：$ref")
                } else {
                    log.warn("todo_done 未匹配到待办: {}", ref)
                    actionLog.append("待办未匹配：$ref")
                }
            } catch (e: Exception) {
                log.warn("todo_done 处理失败: {}", e.message)
                actionLog.append("待办完成失败")
            }
        }
    }

    /**
     * 组装决策 prompt 的强制约束提示（可多段，换行连接）
     *
     * - 连续爬楼期间一直有新消息：打断爬楼，优先回应
     * - 刚 send 完且暂无新回应：按 [noReplyRounds] 递进提示——
     *   第 1 轮提示不要连发；N≥2 轮点明「已连续 N 轮行动但始终没有任何新消息」，
     *   并附上当前心情（含强度数值），由 LLM 自行判断 stay / return / 确有必要再发
     *   （无代码强制退出，去留全交智能体）
     */
    private fun buildHint(
        readMoreWithInsertStreak: Int,
        justSent: String?,
        noReplyRounds: Int,
        newNotes: List<String>
    ): String {
        val parts = mutableListOf<String>()
        if (readMoreWithInsertStreak >= maxReadMoreWithInserts) {
            parts += "有人在等你回应，优先处理新消息（send 或 return），不要再爬楼。"
        }
        if (justSent != null && newNotes.isEmpty()) {
            parts += if (noReplyRounds >= 2) {
                "你已连续 $noReplyRounds 轮行动但始终没有任何新消息（最后发送：「$justSent」）。" +
                    "继续自言自语会很奇怪：可以 return 离开、stay 继续等，或确有必要再发。" +
                    "当前心情：${lucatBot.mood.toPrompt()}"
            } else {
                "你刚发送了「$justSent」，暂无新回应。不要立刻连发；可用 stay 等待一会儿、return 离开，或确实有必要再发。"
            }
        }
        return parts.joinToString("\n")
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

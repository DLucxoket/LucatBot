package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.agent.data.ProactiveCandidate
import org.dlucxoket.lucatbot.bot.agent.data.SessionAction
import org.dlucxoket.lucatbot.bot.agent.data.SessionDecision
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionSummaryLine
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.persona.TimeFormat
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.todo.TodoSettlement
import org.dlucxoket.lucatbot.bot.tool.ToolCatalog
import org.dlucxoket.lucatbot.bot.tool.ToolNotes
import org.dlucxoket.lucatbot.model.session.ConversationId
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
    private val toolCatalog: ToolCatalog = ToolCatalog(),
    /**
     * **P1 空转短路**：没有任何需要 LLM 权衡的东西（无未读、无待办、主动候选
     * 也没到评估间隔）→ 直接返回 null 去空闲，**不烧一轮决策 prompt**
     * （实测选会话每次 ≈5.5k token，空转醒来是调用次数的最大来源）。
     * 置 false 恢复「每次都问 LLM」（测试用；生产默认开）。
     */
    private val shortCircuitWhenIdle: Boolean = true
) {
    private val log = LoggerFactory.getLogger(MessageListController::class.java)

    /**
     * 「可主动找的人」的评估间隔——**空闲醒来每次都问一遍 LLM 太烧**
     * （13 次选会话调用里多数是这种空转），且主动消息本身也不该那么密：
     * 无未读时每 [proactiveEvaluateEveryMs] 才让 LLM 评估一次要不要主动找人
     */
    private val proactiveEvaluateEveryMs: Long = 20 * 60_000L

    /** 上次「主动找人」评估的时间（仅主循环单线程读写） */
    private var lastProactiveEvaluatedAt = 0L

    /** 待办结算（todo_done 路径）：标完成 + 「已办」记忆留痕 + 操作日志 */
    private val todoSettlement = TodoSettlement(todoService, memoryService, actionLog)

    /**
     * 最近一次 IDLE 决策里 LLM 指定的空闲秒数（`params.seconds`；0 = 没给/兜底路径）
     *
     * 单线程主循环写读：[pickSession] 每轮开头清零、IDLE 分支写入，
     * 主循环进空闲前经 [consumeIdleSeconds] 取走（**空闲时长由 LLM 决定**，
     * 只在 LLM 没给时才回退到按心情随机）
     */
    var lastIdleSeconds: Int = 0
        private set

    /** 取走并清零 LLM 指定的空闲秒数（主循环进空闲时调用） */
    fun consumeIdleSeconds(): Int = lastIdleSeconds.also { lastIdleSeconds = 0 }

    /**
     * 走一轮「看消息列表」决策（可含多步工具调用）
     *
     * @param fromIdleWake 是否是空闲唤醒（拼入可主动找的人）
     * @return 选中的会话；null = 去空闲
     */
    fun pickSession(fromIdleWake: Boolean): SessionPick? {
        lastIdleSeconds = 0 // 本轮重新记录（选中会话/工具超限兜底 → 保持 0 = 随机兜底）
        val sessions = collectSessions()
        val unreadCount = sessions.sumOf { it.unreadCount }
        val candidates = if (fromIdleWake) collectProactiveCandidates() else emptyList()

        // P1 空转短路：没未读、没待办、主动候选也没到评估间隔 → 直接去空闲。
        // 这种情况下问 LLM 的结论必然也是 idle——省掉一整轮（≈5.5k token）
        val proactiveDue = candidates.isNotEmpty() &&
            System.currentTimeMillis() - lastProactiveEvaluatedAt >= proactiveEvaluateEveryMs
        if (shortCircuitWhenIdle && unreadCount == 0 && todoService.pending().isEmpty() && !proactiveDue) {
            log.info(
                "消息列表空转（未读 0、待办 0、主动候选{}）→ 短路去空闲，不调 LLM",
                if (candidates.isEmpty()) "无" else "未到评估间隔"
            )
            actionLog.append("消息列表：无未读无待办，直接空闲（没调 LLM）")
            return null
        }
        if (candidates.isNotEmpty()) lastProactiveEvaluatedAt = System.currentTimeMillis()
        actionLog.append("查看消息列表（${unreadCount}条未读）")

        // 冷却中的会话（刚退出还没满 2 分钟）→ 注入「刚退出」提示段，由 **prompt**
        // 约束别马上再进（列表照样可见、不硬过滤——@我/急事模型可自行权衡）
        val cooldownNotes = lucatBot.recentlyLeft().map { (conv, remainMs) ->
            val sec = ((remainMs / 1000).toInt()).coerceAtLeast(1)
            "$conv（${sec} 秒内刚退出过：除非 @我 或特别要紧的未读，否则等它从冷却段消失再进）"
        }
        // 工具上下文（本轮内累积：状态计数/目录/执行结果；选会话侧硬上限更紧）
        val toolNotes = ToolNotes(maxTotalChars = 16_000, maxConsecutiveErrors = 3)

        while (true) {
            val request = SessionPickerRequest(
                persona = lucatBot.persona,
                mood = lucatBot.mood,
                timeHint = TimeAwareness.timeHint(),
                shortTermMemory = buildShortTermMemory(),
                todos = todoService.pendingSummary(),
                actionLog = actionLog.dump(),
                sessions = sessions,
                cooldownNotes = cooldownNotes,
                proactiveCandidates = candidates,
                toolNotes = toolNotes.snapshot(),
                toolNames = toolCatalog.names()
            )

            // 会话维度由 SessionPickerAgent.conversationTag(input) 自挂（固定「消息列表」）
            val decision = sessionPickerAgent.processObject(request)
            applyTodoSideEffects(decision)

            // 防病态值资源护栏（2026-10-01 调整：**只限连续调用错误，不限工具轮数**）
            // ——连续失败达上限 → 兜底去空闲；正常多轮工具不再触发
            val isToolStep = decision.action == SessionAction.LIST_TOOLS ||
                decision.action == SessionAction.CALL_TOOL
            if (isToolStep && toolNotes.errorsExceeded()) {
                actionLog.append("连续工具调用报错超限，去空闲")
                log.warn("选会话连续工具调用报错超限（连续报错 {} 次），兜底 idle", toolNotes.consecutiveErrors)
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
                    lastIdleSeconds = decision.idleSeconds // LLM 决定空闲多久（0 = 随机兜底）
                    log.info("选会话结果: idle（{}）", if (decision.idleSeconds > 0) "${decision.idleSeconds}s" else "时长未指定")
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
                    // 结构化结果（与会话内 CALL_TOOL 同款）：isError 由 ToolCatalog 按
                    // JSON 结构判定，失败打进日志便于排障
                    val call = toolCatalog.invokeDetailed(toolName, decision.toolInput)
                    toolNotes.recordResult(toolName, call.output, call.isError)
                    actionLog.append("调用工具 $toolName")
                    log.info("tool {} → {} 字符", toolName, call.output.length)
                    if (call.isError) {
                        log.warn("工具 {} 返回错误：{}", toolName, call.output.take(300))
                    }
                }
            }
        }
    }

    /**
     * 处理决策信封的待办副作用（在执行 action 之前，与 ConversationController 同语义）
     *
     * todo_add → [TodoService.add]；todo_done → [TodoService.complete]。
     * 每项单独 try/catch：失败只 log + 操作日志，不打断选会话循环。
     */
    private fun applyTodoSideEffects(decision: SessionDecision) {
        decision.todoAdd?.let { content ->
            try {
                // plannedAt = LLM 给的「预计执行时间」（todo_add_time，宽松解析；没给 = null）
                todoService.add(content, plannedAt = decision.todoAddTime)
                actionLog.append("待办+：$content")
            } catch (e: Exception) {
                log.warn("todo_add 写入失败: {}", e.message)
                actionLog.append("待办写入失败")
            }
        }
        decision.todoDone?.let { ref ->
            // 结算统一收口（与会话内同语义）：标完成 + 「已办」记忆留痕 + 操作日志
            todoSettlement.settle(ref, conversationId = null)
        }
    }

    /** 汇总未读/最近会话（含已读与未读） */
    private fun collectSessions(): List<SessionSummaryLine> {
        val unread = lucatBot.getUnreadSummary()
        val lines = mutableListOf<SessionSummaryLine>()
        val now = System.currentTimeMillis()

        for ((convId, count) in unread) {
            val msgs = lucatBot.peekUnread(convId)
            val last = msgs.lastOrNull()
            val cid = ConversationId.parse(convId)
            val id = cid.targetId
            val name = last?.senderName?.ifBlank { id } ?: id
            lines += SessionSummaryLine(
                conversationType = if (cid.isGroup) "群聊" else "私聊",
                name = name,
                id = id,
                time = formatTime(last?.timestamp ?: 0L),
                unreadCount = count,
                lastMessage = last?.content.orEmpty(),
                isAtMe = lucatBot.hasUnreadAtMe(convId),
                isAtAll = lucatBot.hasUnreadAtAll(convId),
                isReplyToMe = lucatBot.hasUnreadReplyToMe(convId),
                botSpokeAgo = chatHistoryService.lastBotSpeakTime(convId)?.let { formatDuration(now - it) },
                suspectedBanDate = lucatBot.suspectedBanSince(convId)?.let { formatBanDate(it) },
                memoryBrief = briefFor(convId)
            )
        }

        // 已读但最近活跃的会话（无未读也展示，模拟「看过列表」）
        val recent = sessionManager.recentConversationIds(withinMinutes = 24 * 60)
            .filter { it !in unread.keys }
        for (convId in recent.take(5)) {
            val entry = sessionManager.getLastMessage(convId) ?: continue
            val cid = ConversationId.parse(convId)
            lines += SessionSummaryLine(
                conversationType = if (cid.isGroup) "群聊" else "私聊",
                name = entry.sender.ifBlank { cid.targetId },
                id = cid.targetId,
                time = formatTime(entry.timestamp),
                unreadCount = 0,
                lastMessage = entry.content,
                botSpokeAgo = chatHistoryService.lastBotSpeakTime(convId)?.let { formatDuration(now - it) },
                suspectedBanDate = lucatBot.suspectedBanSince(convId)?.let { formatBanDate(it) },
                memoryBrief = briefFor(convId)
            )
        }
        return lines
    }

    /**
     * 会话主体的记忆摘要（挂行尾）——**最新优先**：[MemoryService.getMemoriesOf]
     * 按写入时间倒序，take([briefMax]) 即最新几条；单条截 [briefMaxChars] 字
     *
     * 泛概览（### 短期记忆）对得上「有什么记忆」但对不上「哪条属于哪个会话」；
     * 行挂摘要让待办/记忆驱动直接与目标会话关联（实盘：记忆里有任务却选不中
     * 对应会话）。空主体返回 null 不渲染。
     */
    private fun briefFor(conversationId: String): String? {
        val items = memoryService.getMemoriesOf(
            org.dlucxoket.lucatbot.bot.memory.MemorySubject.fromConversation(conversationId)
        ).take(briefMax)
        if (items.isEmpty()) return null
        return items.joinToString("；") { item ->
            val c = item.content
            if (c.length > briefMaxChars) c.take(briefMaxChars) + "…" else c
        }.ifBlank { null }
    }

    private companion object {
        const val briefMax = 3
        const val briefMaxChars = 40
    }

    /** 疑似禁言标记日期（MMdd；跨年 yyyy-MM-dd）——渲染收口 [TimeFormat.banDate] */
    private fun formatBanDate(epochMs: Long): String = TimeFormat.banDate(epochMs)

    /** 相对时长（「我上次发言 X 前」用）——渲染收口 [TimeFormat.duration] */
    private fun formatDuration(ms: Long): String = TimeFormat.duration(ms)

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
            .filter { it.startsWith(ConversationId.PRIVATE_PREFIX) } // 严格前缀判断（无前缀的野键不算私聊候选）
            .take(cap)
            .map { convId ->
                val subject = MemorySubject.fromConversation(convId)
                val cid = ConversationId.parse(convId)
                // 取第一条真实记忆行（跳过「关于此人的记忆：」「事实：」等标题行——
                // 旧行为 firstOrNull() 拿到的永远是标题，hint 恒为废话）
                val memory = memoryService.getMemorySummary(subject).lineSequence()
                    .firstOrNull { it.startsWith("- ") }?.removePrefix("- ").orEmpty()
                val hint = when {
                    memory.isNotBlank() -> memory
                    sessionManager.isActive(convId, withinMinutes = 60) -> "刚才还在聊"
                    else -> "这个点他可能在"
                }
                ProactiveCandidate(
                    name = sessionManager.getLastMessage(convId)?.sender ?: cid.targetId,
                    id = cid.targetId,
                    hint = hint
                )
            }
    }

    /**
     * 消息列表的短期记忆——**一次全量概览**（所有主体、最近优先）
     *
     * 选会话要看得广：不再逐个会话拼小窗口（前 3 会话 × 16 条上限），
     * 改成跨主体概览；具体某个人的细节由 LLM 经 MemoryQueryTool 关键词搜。
     */
    private fun buildShortTermMemory(): String =
        memoryService.getMemoryOverview(primary = null, otherLimit = 3)

    /**
     * 列表行时间——今日 `HH:mm`、跨天 `MM-dd HH:mm`（紧凑）。
     *
     * 渲染收口 [TimeFormat.listLine]（与会话窗口的 [TimeFormat.chatLine] 是两种
     * 具名格式：此处行短省 token，彼处带年月日让 LLM 判断隔天——此前两份
     * 私有拷贝各自演化导致格式分叉，故显式区分而非合并）。
     */
    private fun formatTime(ts: Long): String = TimeFormat.listLine(ts)
}

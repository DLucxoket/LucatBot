package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationAction
import org.dlucxoket.lucatbot.bot.agent.data.ConversationChatLine
import org.dlucxoket.lucatbot.bot.agent.data.ConversationDecision
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.ChatReferenceResolver
import org.dlucxoket.lucatbot.bot.chathistory.formatSenderDisplay
import org.dlucxoket.lucatbot.bot.memory.MemoryExtractor
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.todo.TodoSettlement
import org.dlucxoket.lucatbot.bot.tool.NapCatImageAction
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
    /** 图片附件取材（图片通道）：地址 → data URI，带缓存 */
    private val imageResolver: org.dlucxoket.lucatbot.bot.llm.ImageResolver = org.dlucxoket.lucatbot.bot.llm.ImageResolver(),
    /**
     * 协议侧取图（NapCat `get_image`：ref → 本地路径/新鲜 URL，失败 null）——
     * 图片必达链路的兜底来源。**依赖注入**：测试传替身即可覆盖救援/降级路径。
     */
    private val imageSource: (String) -> String? = { NapCatImageAction.localSource(lucatBot, it) },
    /**
     * 分段发送的条间等待（默认走 [LucatBot.awaitPendingInsert]：等满 delay 或被
     * 新消息提前唤醒；InterruptedException 向上传播 → 退出会话）。**依赖注入**：
     * 测试传记录型替身即可断言「只在条间计时、第一条不等」而无需真睡。
     */
    private val segmentDelayWait: (conversationId: String, ms: Long) -> Boolean =
        { id, ms -> lucatBot.awaitPendingInsert(id, ms) },
    /**
     * send 的 `wait_message` 计时——发送后原地等新消息：来了即唤醒、等满即超时。
     * 默认走 [LucatBot.awaitPendingInsert]；**依赖注入**：测试传记录型替身即可断言
     * 「超时直接退出 / 被唤醒继续」而无需真睡
     */
    private val replyWait: (conversationId: String, ms: Long) -> Boolean =
        { id, ms -> lucatBot.awaitPendingInsert(id, ms) },
    /** 初始读史条数 */
    private val initialHistoryCount: Int = 10,
    /** 退出会话时记忆整理读取的聊天记录条数 */
    private val reflectHistoryCount: Int = 30,
    /**
     * 记忆整理任务执行器——默认单线程 daemon（异步，退出整理不阻塞返回消息列表）；
     * 测试注入内联执行器（`Executor { it.run() }`）变同步，断言无竞态
     */
    private val reflectExecutor: java.util.concurrent.Executor = MemoryReflector.asyncExecutor(),
    /**
     * 已读水位——进会话即标已读（消费未读后写水位，重启据此重建未读）；
     * 默认 temp 文件（测试不污染 data/），生产由 LoopConfig 显式注入真 bean
     */
    private val readStateService: org.dlucxoket.lucatbot.bot.readstate.ReadStateService =
        org.dlucxoket.lucatbot.bot.readstate.ReadStateService(
            chatHistoryService,
            org.dlucxoket.lucatbot.bot.chathistory.JsonFileReadStateRepository(
                java.io.File.createTempFile("read-state", ".json").apply { delete() }.absolutePath
            )
        ),
    /**
     * 固定短记——**固定放进决策 prompt 的短期记忆**（生产由 LoopConfig 注入
     * 持久化 bean；null = 既不注入也不结算，测试默认）
     */
    private val fixedMemory: org.dlucxoket.lucatbot.bot.memory.FixedMemory? = null,
    /**
     * 连续 read_more 且一直有新消息插入的打断轮数
     */
    private val maxReadMoreWithInserts: Int = 3,
    /** 连续发送失败上限（病态护栏：如被禁言还反复试发，达上限强制收尾） */
    private val maxSendFailures: Int = 3,
    /**
     * 连续停留且没人接话的次数上限（病态护栏）：到此仍无新消息，hint 要求本轮
     * return；模型还输出 stay → 强制退出。有新消息即清零，正常等待不受影响
     */
    private val maxIdleStays: Int = 3,
    /** 每轮 prompt 的图片附件上限（请求体体积闸；取最近的几张） */
    private val maxPromptImages: Int = 6
) {
    private val log = LoggerFactory.getLogger(ConversationController::class.java)

    /** memory_queries 每轮搜索词上限（prompt 写「数量不限、宁多勿少」，这里只防极端刷量） */
    private val maxMemoryQueries = 12

    /** 记忆搜索结果行数上限（一次注入「短期记忆」段的体量闸） */
    private val maxMemoryHitLines = 24

    /**
     * 退出记忆整理（幂等、失败不影响退出）——单一职责组件，见 [MemoryReflector]
     *
     * 正常退出、停机收尾都经它；同会话去重保护在组件内部。
     */
    private val reflector = MemoryReflector(
        memoryAgent = memoryAgent,
        memoryService = memoryService,
        chatHistoryService = chatHistoryService,
        actionLog = actionLog,
        reflectHistoryCount = reflectHistoryCount,
        executor = reflectExecutor,
        // 待办兜底结算：退出整理那轮 LLM 顺带核对「本次对话把哪些待办办完了」
        todoService = todoService,
        // 固定短记：同一轮整理顺带整体覆盖更新（fixed_memory 输出）
        fixedMemory = fixedMemory
    )

    /** 待办结算（todo_done 路径）：标完成 + 「已办」记忆留痕 + 操作日志 */
    private val todoSettlement = TodoSettlement(todoService, memoryService, actionLog)

    /**
     * 跑完整的会话内循环，直到离开
     */
    fun run(pick: SessionPick) {
        val conversationId = toConversationId(pick)
        val isGroup = pick.conversationType == SessionPickType.GROUP
        val proactive = pick.conversationType == SessionPickType.PROACTIVE

        // 重置幂等保护：同一会话再次进入时允许重新整理
        reflector.beginConversation()

        lucatBot.enterConversation(
            conversationId,
            if (isGroup) LucatBot.ConversationType.GROUP else LucatBot.ConversationType.PRIVATE
        )
        lucatBot.mood = lucatBot.mood.boost()
        actionLog.append(
            "进入${if (isGroup) "群聊" else "私聊"} ${pick.targetId}" + if (proactive) "（主动找人）" else "",
            conversationId = conversationId
        )
        log.info("进入会话 {}（{}{}）", conversationId,
            if (isGroup) "群聊" else "私聊", if (proactive) "，主动发起" else "")

        // 进入会话即消费未读并标已读（水位推进 → 重启后未读=水位之后的消息）
        lucatBot.consumeUnread(conversationId)
        readStateService.markRead(conversationId)
        // 丢弃陈旧的让位请求（通知等锁超时后可能残留——不属于本会话，开跑前清掉）
        lucatBot.consumeSessionYield()

        try {
            loop(conversationId, isGroup, proactive)
            // loop() 正常返回（含内部 return）→ 异步整理一次记忆：任务丢进执行器
            // 立即返回，返回消息列表与主循环下一步不等 LLM（停机时由
            // ProgramShutdown 先 awaitPendingReflects 排干、再同步补做）
            // loop() 抛异常则跳过整理，直接走 finally
            reflector.reflectAsync(conversationId)
        } finally {
            // 「返回消息列表」由 RETURN 分支带 reason 记入（本处不再重复）；
            // 中断/异常退出路径各自在中断点补记，异常有 log.error 兜底
            // 退出即进冷却：选会话短期内不再选它（防刚退回又马上进来空转）
            lucatBot.markConversationLeft(conversationId)
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
        // 连续「停留且没人接话」的次数（有新消息即清零）——stay 几次仍无人回应
        // 就该离开：hint 升级要求 return，模型仍不走时由 maxIdleStays 强制兜底
        var stayStreak = 0
        // 记忆搜索结果（LLM 经顶层 memory_queries 请求）：从下一轮起持续注入
        // 「短期记忆」段——**不清空，直到再次请求才整体覆盖**；没请求过则该段逐字不变
        val pendingMemoryHits = mutableListOf<String>()
        // 发送失败状态（注入下一轮 prompt 通知 LLM，由它决定去留；连续失败达上限强制收尾）
        var sendFailureNote: String? = null
        var sendFailStreak = 0
        // 按需供图：上一轮 view_images 的取图成果——仅附到紧跟的下一轮然后丢弃
        //（每轮全新请求、图内容看过即在当轮决策里消化，不重复携带省 token）
        var pendingViewImages: PromptAttachments? = null
        // 工具上下文（本会话内累积：状态计数/目录/执行结果，随每轮注入 prompt）
        val toolNotes = ToolNotes(maxTotalChars = 16_000, hardLimitSteps = 12)
        // 会话身份：群名进会话时查一次（网络调用不每轮打）；私聊每轮现算（见 buildIdentity）
        val cachedGroupName = if (isGroup) fetchGroupName(conversationId) else null
        // 机器人自己的 QQ 号（LLM 判断「@的是不是我」的依据；未连接 → 0）
        val selfQq = lucatBot.shiroBot?.selfId ?: 0L

        // 决策轮次（可观测性：每轮 LLM 调用前后都打日志——此前调用完全静默，
        // 实盘怀疑「没调决策智能体」其实是日志盲区）
        var round = 0

        // 会话内记日志（自动带上所处会话——操作日志结构化落库的 conversationId）
        fun logAct(action: String, reason: String? = null) =
            actionLog.append(action, reason, conversationId)

        while (true) {
            // 让位检查点：通知通道要当场跳转、当前会话占着执行锁 → 让出。
            // 让位 = 退出本会话循环（释放锁给通知），并记「回到会话继续」的待办——
            // **被禁言的会话除外**（列表已标【疑似禁言】，不会回去，记了也白记）
            if (lucatBot.consumeSessionYield()) {
                if (lucatBot.suspectedBanSince(conversationId) == null) {
                    todoService.add("回到 $conversationId 继续刚才的对话（被新通知打断）", conversationId)
                    logAct("被新通知打断，切出（已记待办：回到 $conversationId 继续）")
                } else {
                    logAct("被新通知打断，切出（禁言会话，不记返回待办）")
                }
                log.info("会话 {} 让位给通知跳转", conversationId)
                return
            }
            // 1) 把待插入的新消息以系统注释形式合并
            val inserts = lucatBot.drainPendingInserts(conversationId)
            if (inserts.isNotEmpty()) {
                justSent = null // 有新回应就不再提示旧发送
                noReplyRounds = 0
                stayStreak = 0 // 有人接话 → 停留连击清零
            } else if (justSent != null) {
                // 已发出消息且本轮仍没有任何新消息 → 递进一轮（衡量连续无回应）
                noReplyRounds++
            }
            // 2) 读历史（Tool → OOP）
            val historyResult = chatHistoryTool.processObject(
                ChatHistoryRequest(
                    targetId = conversationId.substringAfter(":").toLongOrNull() ?: 0L,
                    count = historyCount,
                    type = if (isGroup) "group" else "private"
                )
            )

            // 引用解析索引：把窗口内的 @QQ / 回复 #消息id 改写成
            // 「@昵称(QQ)」「回复 @某人[时间]：被回复内容摘录」，LLM 才知道在艾特谁、回的是哪句
            val refIndex = ChatReferenceResolver.indexOf(
                historyResult.messages.map {
                    ChatReferenceResolver.WindowEntry(
                        messageId = it.messageId,
                        senderId = it.senderId,
                        senderName = it.sender,
                        timestamp = it.timestamp,
                        content = it.content
                    )
                }
            )
            fun resolveRef(content: String): String =
                ChatReferenceResolver.resolve(content, refIndex) { ts -> formatTime(ts) }

            val recentLines = historyResult.messages.map {
                ConversationChatLine(
                    // 展示名 + 名片/QQ昵称注解（同号不同名时点破是同一人；改名后为新展示名）
                    sender = formatSenderDisplay(it.sender, it.nickname),
                    senderId = it.senderId.takeIf { id -> id != 0L }?.toString().orEmpty(),
                    isMe = it.isFromBot || it.sender == "bot" || it.sender == "我",
                    time = formatTime(it.timestamp),
                    content = resolveRef(it.content),
                    messageId = it.messageId
                )
            }
            // 新消息注释同样解析（它们引用的多是窗口内刚出现的消息）；
            // 带 message_id 时附（#id）标记，本轮就能对新消息发起回复
            val newNotes = inserts.map {
                val idTag = it.messageId?.let { id -> "（#$id）" } ?: ""
                "（新消息）${it.senderName}(${it.senderId}) [${formatTime(it.timestamp)}]$idTag：${resolveRef(it.content)}"
            }

            // 图片通道（**按需供图**）：默认不取材、不附内容——只有上一轮
            // view_images 请求过才把成果附到本轮（一次性）；未请求时附件与
            // 缺失清单皆空，省下载也省 token
            val imageSources = historyResult.messages.map {
                ImageLine(it.content, it.imageUrls.orEmpty(), it.messageId)
            } + inserts.map { ImageLine(it.content, it.imageUrls.orEmpty(), it.messageId) }
            val attachments = pendingViewImages ?: PromptAttachments(emptyList(), emptyList())
            pendingViewImages = null

            // 3) 组请求并决策（带爬楼状态，由 LLM 自行决定继续/回复/离开）
            val reachedHistoryStart = historyResult.messages.size < historyCount
            // 上一轮/更早 memory_queries 的搜索结果 → 追加在记忆段末尾（**不清空**：
            // 一直带着，直到下次给出 memory_queries 才被新结果整体覆盖；
            // 从没请求过 → 空串，记忆段与原来完全一致）
            val memoryHitsBlock = if (pendingMemoryHits.isEmpty()) {
                ""
            } else {
                "\n【记忆搜索结果（你之前用 memory_queries 请求的关键词；再次请求会覆盖）】\n" +
                    pendingMemoryHits.joinToString("\n") { "- $it" }
            }
            val request = ConversationRequest(
                persona = lucatBot.persona,
                mood = lucatBot.mood,
                timeHint = TimeAwareness.timeHint(),
                conversationType = if (isGroup) "群聊" else "私聊",
                conversationName = pickName(conversationId),
                conversationIdentity = buildIdentity(conversationId, isGroup, cachedGroupName),
                conversationId = conversationId,
                selfQq = selfQq,
                // 全量概览：当前对象主体记忆 + 其他会话记忆少量（带主体标签）——
                // 不再只注入当前会话；缺的细节由 LLM 经 MemoryQueryTool 关键词搜
                shortTermMemory = memoryService.getMemoryOverview(
                    primary = MemorySubject.fromConversation(conversationId)
                ) + memoryHitsBlock + sessionManager.getFatigueHint(conversationId),
                todos = todoService.summaryForPrompt(),
                actionLog = actionLog.dump(),
                fixedMemory = fixedMemory?.current().orEmpty(),
                recentMessages = recentLines,
                newMessageNotes = newNotes,
                proactive = mustIcebreak,
                readMoreRounds = readMoreRounds,
                historyCovered = historyCount,
                reachedHistoryStart = reachedHistoryStart,
                hint = buildHint(readMoreWithInsertStreak, justSent, noReplyRounds, newNotes, sendFailureNote, stayStreak),
                toolNotes = toolNotes.snapshot(),
                images = attachments.images,
                missingImages = attachments.missing
            )

            round++
            log.info("第 {} 轮决策：调用会话智能体（历史 {} 条、新消息 {} 条、图片 {} 张）",
                round, historyCount, inserts.size, attachments.images.size)
            val decisionStartMs = System.currentTimeMillis()
            var decision = conversationAgent.processObject(request)
            // 决策行带上关键参数（等回信/停留秒数）——否则「发送后为什么 5 分钟没下一步」
            // 在日志里无从判断（实盘：wait_message 300s 静默等待被当成卡死）
            val paramsNote = when (decision.action) {
                ConversationAction.SEND ->
                    decision.waitMessageSeconds.takeIf { it > 0 }?.let { "［等回信 ${it}s］" }.orEmpty()
                ConversationAction.STAY -> "［停留 ${decision.staySeconds}s］"
                else -> ""
            }
            log.info("第 {} 轮决策 → {}（{}ms）{}{}", round, decision.action,
                System.currentTimeMillis() - decisionStartMs, paramsNote,
                decision.reason?.let { "：$it" } ?: "")
            // send 动作的 remember 延后到发送成功才写（防「没发出去却记了『我回复了』」）；
            // 上一轮发送仍失败（sendFailureNote 未清）期间，非 send 动作的 remember
            // 一律忽略——失败现场 LLM 补记的多与没发出的消息相关，防假记忆
            // memory_queries：**当轮**关键词搜，结果注入**下一轮**的「短期记忆」段
            searchMemories(decision, pendingMemoryHits)
            val deferredRemember = applyDecisionSideEffects(
                decision, conversationId,
                suppressRemember = sendFailureNote != null
            )

            // proactive 破冰：首条必须 send（查目录/调工具/看图是前置动作，不视为破冰失败）
            val isToolStep = decision.action == ConversationAction.LIST_TOOLS ||
                decision.action == ConversationAction.CALL_TOOL
            val isPrepStep = isToolStep || decision.action == ConversationAction.VIEW_IMAGES
            if (mustIcebreak && decision.action != ConversationAction.SEND && !isPrepStep) {
                log.info("主动发起的会话尚未破冰，强制首条 send（原动作 {}）", decision.action)
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
                log.info("连续爬楼 {} 轮且一直有新消息，强制打断", readMoreWithInsertStreak)
                decision = ConversationDecision.forceReturn("连续爬楼期间一直有新消息，强制回应或离开")
            }

            // 防病态值资源护栏：连续工具步达硬上限 → 强制收尾（与 stay 钳制同性质，
            // 不限制正常工具使用，只兜底「无限烧 token」的病态路径）
            if (isToolStep && toolNotes.exceeded()) {
                decision = ConversationDecision.forceReturn("连续工具步已达上限，先结束本轮工具使用")
            }
            // 非工具决策（含被强制的 return/send）→ 重置连续工具步计数
            if (decision.action != ConversationAction.LIST_TOOLS &&
                decision.action != ConversationAction.CALL_TOOL
            ) {
                toolNotes.resetStreak()
            }

            // 本轮决策的简短动机（reason ≤30 字）——**挂进操作日志的动作行**：
            // 「操作本身 + 内心动机」同一条进全局 ActionLog（跨会话、滚动窗口、
            // 持久化），LLM 下一轮能看到自己刚做了什么、为什么做；不额外加行省 token
            // reason 存**裸文本**（不带括号）——ActionLog 结构化存储后由 dump()
            // 统一渲染成「操作（原因）」，此处再包一层会出现「（（…））」双括号
            val why: String? = decision.reason?.trim()?.take(30)?.takeIf { it.isNotEmpty() }

            val hadInserts = newNotes.isNotEmpty()

            // 病态护栏：连续停留 [maxIdleStays] 次始终没人接话——hint 已在上一轮
            // 要求「本轮必须 return」，模型仍输出 stay 时强制收尾（只拦 STAY：
            // send/read_more/tool 等正常动作不受影响；有新消息时 stayStreak 已清零）
            if (decision.action == ConversationAction.STAY && stayStreak >= maxIdleStays) {
                logAct("连续停留 $stayStreak 次无人接话，强制返回消息列表", why)
                log.warn("连续停留 {} 次无新消息，强制离开会话", stayStreak)
                return
            }

            when (decision.action) {
                ConversationAction.READ_MORE -> {
                    val count = decision.readCount
                    historyCount += count
                    readMoreRounds++
                    if (hadInserts) readMoreWithInsertStreak++ else readMoreWithInsertStreak = 0
                    if (!hadInserts) {
                        lucatBot.mood = lucatBot.mood.decay()
                    }
                    logAct("爬楼 $count 条", why)
                    log.info("read_more → 累计读取 {}", historyCount)
                }

                ConversationAction.SEND -> {
                    // 分段发送：content 数组逐条发（delay 模拟打字、可被新消息唤醒）；
                    // 中途失败即停剩余（已发的照常记账——对方看得见），提示带第 k/N 条
                    val outgoing = decision.resolveOutgoing()
                    val sentContents = mutableListOf<String>()
                    var failDetail: String? = null
                    var failIndex = 0
                    var interrupted = false
                    for ((idx, msg) in outgoing.withIndex()) {
                        // 条间打字延迟：**上一条发完之后才开始计时**（第一条没有
                        // 上一条、不等）——同步顺序执行天然保证计时起点=上一条
                        // 发送完成；等满或被新消息提前唤醒后发本条
                        if (idx > 0) {
                            val delayMs = (msg.delaySeconds * 1000).toLong().coerceIn(0L, 30_000L)
                            if (delayMs > 0) {
                                try {
                                    if (segmentDelayWait(conversationId, delayMs)) {
                                        log.info("第 {} 条发送前条间延迟被新消息提前唤醒（{}ms）", idx + 1, delayMs)
                                    }
                                } catch (e: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    log.info("分段发送延迟被中断，退出会话")
                                    logAct("发送延迟被中断，退出")
                                    interrupted = true
                                    break
                                }
                            }
                        }
                        val sendResult = sendMessageTool.processObject(
                            SendMessageRequest(
                                conversationId = conversationId,
                                content = msg.text.orEmpty(),
                                replyToId = msg.replyToId,
                                members = msg.members
                            )
                        )
                        if (!sendResult.success) {
                            failDetail = sendResult.error.orEmpty()
                            failIndex = idx + 1
                            break
                        }
                        val sentText = msg.summarize()
                        sentContents += sentText
                        // 每条成功即写短期记忆（与 Tool 写聊天记录同步——分段发送
                        // 期间的每条机器人消息都立即可查，不等整个序列发完）
                        sessionManager.addMessage(
                            conversationId, lucatBot.persona.name, sentText, isBot = true
                        )
                    }
                    // 全部条目发完（或中途失败/中断）才进入下一轮决策——
                    // 这期间到达的别人消息在待插入队列里，下一轮 drain 进 prompt

                    // 已发部分统一记账（部分成功也算发出去了——Tool 已写聊天记录，
                    // 这里补正则提取/操作日志/justSent；短期记忆已逐条写）
                    if (sentContents.isNotEmpty()) {
                        val sentText = sentContents.joinToString(" ")
                        sendFailStreak = 0
                        sendFailureNote = null
                        if (failDetail == null) {
                            // 全部成功才写延后的 remember（「我回复了xxx」此时才属实），
                            // 并解除疑似禁言标记（发出去了 = 没被禁）
                            deferredRemember?.let { writeRemember(it, conversationId) }
                            lucatBot.clearSuspectedBan(conversationId)
                        } else if (deferredRemember != null) {
                            // 部分失败：remember 语义覆盖整段发送、不可靠 → 丢弃
                            log.info("部分发送失败，丢弃随发送写入的记忆: {}", deferredRemember)
                        }
                        // 只从「对方最后一条消息」提取记忆：recentLines 末尾可能是
                        // 机器人自己的回复，把 bot 的话喂给正则会误记成用户的长期 FACT
                        val lastUser = recentLines.lastOrNull { !it.isMe }
                        val lastUserContent = lastUser?.content.orEmpty()
                        if (lastUserContent.isNotBlank()) {
                            val extracted = MemoryExtractor.extract(
                                MemorySubject.fromConversation(conversationId),
                                lastUserContent
                            )
                            if (extracted.isNotEmpty()) {
                                memoryService.saveAll(extracted)
                                // 该行已产出记忆 → 打标，退出整理时不再被改写复读
                                chatHistoryService.markMemorized(
                                    conversationId, lastUser?.messageId, lastUserContent
                                )
                            }
                        }
                        logAct("回复：$sentText", why)
                        log.info("发送成功 {} 条：{}", sentContents.size, sentText.take(80))
                        lucatBot.mood = lucatBot.mood.boost()
                        mustIcebreak = false
                        readMoreWithInsertStreak = 0
                        // 发完不等待，立即进入下一轮决策；连发由 hint 约束
                        justSent = sentText
                    }
                    if (interrupted) return

                    when {
                        outgoing.isEmpty() -> {
                            // LLM 输出的 content 空/全是无效条目——如实告知重发；
                            // 属输出格式问题（非通道失败），不计 sendFailStreak
                            log.warn("send 的 content 无有效消息，未发出")
                            logAct("发送内容无效", why)
                            sendFailureNote = "上一轮你 send 的 content 为空或无法识别任何有效消息，" +
                                "没有发出任何内容——按 send 动作说明重新组织（JSON 数组，每条含 members）再发。"
                            mustIcebreak = false
                        }
                        failDetail != null -> {
                            // 失败消息不入档（SendMessageTool 仅 ok 后写记录）——
                            // LLM 下一轮看到失败通知后自行决定去留
                            sendFailStreak++
                            val detail = if (outgoing.size > 1) {
                                "第 $failIndex/${outgoing.size} 条失败：$failDetail"
                            } else {
                                failDetail
                            }
                            log.warn("发送失败: {}", detail)
                            logAct(
                                if (sentContents.isEmpty()) "发送失败"
                                else "发送部分失败（已发 ${sentContents.size}/${outgoing.size}）",
                                why
                            )
                            sendFailureNote = buildSendFailureNote(detail, isGroup)
                            // 疑似禁言打点：会话列表渲染【疑似禁言 MM-dd】（发送成功自动清除）
                            if (bannedRetCode(detail) != null) {
                                lucatBot.markSuspectedBan(conversationId)
                            }
                            mustIcebreak = false // 破冰已尝试——失败后去留交给 LLM，不许再强制重发
                            if (sentContents.isEmpty() && deferredRemember != null) {
                                // 一条都没发出去就不能记「我回复了…」——丢弃（LLM 可下轮重新 decide）
                                log.info("发送失败，丢弃随发送写入的记忆: {}", deferredRemember)
                            }
                            if (sendFailStreak >= maxSendFailures) {
                                // 病态护栏：连续失败达上限（如一直被禁言还反复试发）强制收尾
                                logAct("连续发送失败 $sendFailStreak 次，返回消息列表")
                                log.warn("连续发送失败 {} 次，强制离开会话", sendFailStreak)
                                return
                            }
                        }
                    }

                    // send 带 wait_message：发送成功后**原地等回信**——期间来了新消息
                    // （或让位请求）立即唤醒、进下一轮处理；等满仍没有任何新消息 =
                    // 没人接话 → 不再空转，直接退出会话。失败/空发不等（失败提示链路接管）
                    val waitSec = decision.waitMessageSeconds
                    if (waitSec > 0 && sentContents.isNotEmpty() && failDetail == null) {
                        val waitMs = waitSec.coerceIn(1, 60) * 1000L
                        // 等待开始就打点（否则等待期间像「卡死」——实盘反馈发送后再无日志）
                        log.info("发送后原地等回信 {}s（期间来消息立即唤醒；等满无新消息则退出会话）", waitMs / 1000)
                        val woken = try {
                            replyWait(conversationId, waitMs)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            log.info("发送后等回信被中断，退出会话")
                            logAct("等回信被中断，退出")
                            return
                        }
                        if (woken) {
                            // 唤醒可能是新消息、也可能是通知让位请求（复用同一等待）
                            val whyWait = if (lucatBot.sessionYieldRequested) "被通知打断" else "新消息提前唤醒"
                            log.info("发送后等回信 {}s，被{}", waitSec, whyWait)
                            logAct("等回信 ${waitSec}s（$whyWait）")
                        } else {
                            log.info("发送后等回信 {}s 仍无新消息，退出会话", waitSec)
                            logAct("等回信 ${waitSec}s 无人回复，退出")
                            return
                        }
                    }
                }

                ConversationAction.STAY -> {
                    // 上限防病态值（非退出限制）；prompt 建议 LLM 通常给 10~120 秒
                    val sec = decision.staySeconds.coerceIn(1, 1800)
                    // 计时等待：当前会话在时间到之前来了新消息 → 立即唤醒（取消剩余计时），否则等满
                    val interrupted = try {
                        val woken = lucatBot.awaitPendingInsert(conversationId, sec * 1000L)
                        if (woken) {
                            // 唤醒可能是新消息、也可能是通知让位请求（requestSessionYield 复用同一等待）
                            val byYield = lucatBot.sessionYieldRequested
                            val whyStay = if (byYield) "被通知打断" else "新消息提前唤醒"
                            log.info("停留计划 {}s，被{}", sec, why.orEmpty())
                            logAct("停留 ${sec}s（$whyStay）", why)
                        } else {
                            logAct("停留 ${sec}s", why)
                        }
                        false
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        log.info("停留 {}s 被中断，退出会话", sec)
                        logAct("停留被中断，退出")
                        true
                    }
                    if (interrupted) return
                    readMoreWithInsertStreak = 0 // 停留打断连续爬楼计数
                    // 没被新消息唤醒的停留才算「白等」——累计连击交 hint/强制退出
                    if (!hadInserts) stayStreak++
                }

                ConversationAction.RETURN -> {
                    log.info("决策 return，离开会话")
                    logAct("返回消息列表", why)
                    return
                }

                ConversationAction.LIST_TOOLS -> {
                    val query = decision.toolQuery
                    toolNotes.recordCatalog(query, toolCatalog.catalog(query))
                    logAct("查看工具目录（${toolCatalog.count(query)} 个）", why)
                    log.info("list_tools query={} → {} 个", query ?: "*", toolCatalog.count(query))
                }

                ConversationAction.CALL_TOOL -> {
                    val toolName = decision.toolName.orEmpty()
                    val result = toolCatalog.invoke(toolName, decision.toolInput)
                    toolNotes.recordResult(toolName, result)
                    logAct("调用工具 $toolName", why)
                    log.info("tool {} → {} 字符", toolName, result.length)
                }

                ConversationAction.VIEW_IMAGES -> {
                    // 按需供图：当轮取图（下载/救援/失败清单），附到**下一轮**请求
                    val refs = decision.viewRefs.take(maxPromptImages)
                    val att = gatherImagesForRefs(imageSources, refs)
                    pendingViewImages = att
                    logAct(
                        if (att.missing.isEmpty()) "看图 ${att.images.size} 张"
                        else "看图 ${att.images.size} 张（${att.missing.size} 张没拿到）",
                        why
                    )
                    log.info("view_images 请求 {} 张 → 附件 {}、缺失 {}", refs.size, att.images.size, att.missing)
                }
            }
        }
    }

    /**
     * 停机收尾入口：程序停止时若停在某个会话里，补做一次退出记忆整理。
     *
     * 幂等由 [MemoryReflector] 内部保证（正常退出已整理过则直接跳过）；
     * 不在会话中时为空操作。
     */
    fun reflectForShutdown() {
        val state = lucatBot.state
        if (state is LucatBot.State.InConversation) {
            log.info("停机收尾：会话 {} 尚未整理记忆，先补一次", state.conversationId)
            reflector.reflect(state.conversationId)
        }
    }

    /**
     * 停机收尾入口：等待已排队的异步记忆整理落地（在 [reflectForShutdown] 之前调用）
     *
     * @return true = 全部落地；false = 超时仍有任务在跑
     */
    fun awaitPendingReflects(timeoutMs: Long): Boolean = reflector.awaitPending(timeoutMs)

    /**
     * 处理决策信封的副作用字段（在执行 action 之前）
     *
     * todo_add / todo_done → 待办清单；remember → 短期记忆，但
     * **send 动作的 remember 延后到发送成功后才写**（返回值即待写的 remember）——
     * 否则 LLM 边发边记的「我回复了 xxx」会在发送失败（如被禁言）时留下假记忆。
     *
     * @param suppressRemember 发送仍处于失败状态（sendFailureNote 未清）——
     *   非 send 动作的 remember 一律忽略（失败现场补记的多与没发出的消息相关）
     * 每项单独 try/catch：失败只 log，不中断主循环；
     * 成功与失败都追加一行操作日志（记住 / 待办+ / 待办完成 / 写入失败）。
     *
     * @return 被延后的 remember（非 send 动作或无 remember → null，已即时写入）
     */
    private fun applyDecisionSideEffects(
        decision: ConversationDecision,
        conversationId: String,
        suppressRemember: Boolean = false
    ): String? {
        val deferredRemember = decision.remember?.takeIf { decision.action == ConversationAction.SEND }
        decision.remember
            ?.takeIf { decision.action != ConversationAction.SEND }
            ?.let {
                if (suppressRemember) {
                    log.info("上一轮发送仍失败，忽略 remember（防把没发出去的话记进记忆）: {}", it)
                } else {
                    writeRemember(it, conversationId)
                }
            }
        decision.todo_add?.let { content ->
            try {
                todoService.add(content, conversationId)
                actionLog.append("待办+：$content", conversationId = conversationId)
            } catch (e: Exception) {
                log.warn("todo_add 写入失败: {}", e.message)
                actionLog.append("待办写入失败", conversationId = conversationId)
            }
        }
        decision.todo_done?.let { ref ->
            // 结算统一收口：标完成 + 写「已办：…」记忆留痕 + 操作日志（失败只 log）
            todoSettlement.settle(ref, conversationId)
        }
        return deferredRemember
    }

    /**
     * 执行本轮的 memory_queries（顶层可选数组：人名/QQ号/聊天里的词/想不起来的细节）
     * **全局关键词搜**（不限主体——人名、QQ号 常跨会话），每词最多取 3 条、
     * 每轮最多 [maxMemoryQueries] 词（prompt 明确「数量不限、宁多勿少」，
     * 上限只防一次甩几十个关键词把结果段撑爆）；结果写进 [sink]，从下一轮起一直
     * 注入「短期记忆」段末尾——**不清空，直到再次给出 memory_queries 才整体覆盖**
     * （**从没请求过 → 记忆段与原来完全一致**）。
     */
    private fun searchMemories(decision: ConversationDecision, sink: MutableList<String>) {
        if (decision.memoryQueries.isEmpty()) return
        sink.clear() // 新一轮搜索 → **整体覆盖**旧结果（没请求就一直带着）
        decision.memoryQueries.take(maxMemoryQueries).forEach { query ->
            try {
                val hits = memoryService.search(query).take(3)
                sink += "「$query」：" +
                    if (hits.isEmpty()) "（没搜到）" else hits.joinToString("；") { it.content }
                log.info("记忆搜索「{}」→ {} 条", query, hits.size)
            } catch (e: Exception) {
                log.warn("记忆搜索失败（{}）: {}", query, e.message)
                sink += "「$query」：（搜索失败）"
            }
        }
        while (sink.size > maxMemoryHitLines) sink.removeAt(0) // 只保留最近几条，防长期膨胀
    }

    /** 写一条短期记忆 + 操作日志（失败只 log，不中断主循环） */
    private fun writeRemember(note: String, conversationId: String) {
        try {
            memoryService.rememberEvent(MemorySubject.fromConversation(conversationId), note)
            actionLog.append("记住：$note", conversationId = conversationId)
        } catch (e: Exception) {
            log.warn("remember 写入失败: {}", e.message)
            actionLog.append("记忆写入失败", conversationId = conversationId)
        }
    }

    /** 收集图片附件的一行来源：正文 + 图片地址列表 + 所在消息的 message_id（刷新用） */
    private data class ImageLine(val content: String, val urls: List<String>, val messageId: Long?)

    /** 本轮图片收集结果：附件 + 未能附上的文件标签（缺失事实要如实交给 LLM） */
    private data class PromptAttachments(
        val images: List<org.dlucxoket.lucatbot.bot.llm.PromptImage>,
        val missing: List<String>
    )

    /**
     * 按需取图（**只处理 LLM view_images 请求的文件标签**）
     *
     * @param sources 每条消息的正文 + 图片地址——地址与正文里
     *   `[图片 file=x]` 占位符按下标对应（入档时同序收集）
     * @param requested LLM 请求查看的文件标签（占位符里的 x）
     *
     * 规则：
     * - 只匹配 requested 的占位符才下载/救援（同址缓存复用）；**没请求的图
     *   一律不碰**——按需供图省下载也省 token
     * - 拿不到的（下载失败/旧数据无地址/请求了但查无此 ref）进缺失清单——
     *   下一轮 prompt 如实告知，LLM 才不会把没收到的图当成看过
     * - [VisionSupport] 确认模型不支持识图 → 全部直接进缺失清单（不下载）
     * - 整体去重后**只取最近的 [maxPromptImages] 张**；取材失败绝不中断决策轮
     */
    private fun gatherImagesForRefs(sources: List<ImageLine>, requested: List<String>): PromptAttachments {
        val wanted = requested.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return PromptAttachments(emptyList(), emptyList())
        // 模型已确认不支持识图（进程级记忆，不持久化）→ 不给图片内容：
        // 跳过下载与救援（省资源），refs 如实进缺失清单，交给 prompt 现有约束链
        if (org.dlucxoket.lucatbot.bot.llm.VisionSupport.unsupported()) {
            return PromptAttachments(emptyList(), wanted.toList().takeLast(maxPromptImages))
        }
        val images = mutableListOf<org.dlucxoket.lucatbot.bot.llm.PromptImage>()
        val missing = linkedSetOf<String>()
        val matched = linkedSetOf<String>()
        for (line in sources) {
            val refs = org.dlucxoket.lucatbot.model.message.AssetMarkers.refs(line.content)
            line.urls.forEachIndexed { i, url ->
                val ref = refs.getOrNull(i)?.takeIf { it.isNotBlank() }
                    ?: url.substringAfterLast('/').substringBefore('?')
                if (ref !in wanted) return@forEachIndexed
                matched += ref
                // 兜底 runCatching：ImageResolver 契约是失败即 null，但取材
                // 绝不允许中断决策轮（图片通道不背会话链路的锅）
                val img = runCatching {
                    imageResolver.resolve(url, ref, refreshSupplier(line.messageId, ref))
                }.getOrNull()
                if (img != null) images += img else missing += ref
            }
            // 占位符有、地址没有（旧数据/收集不全）→ 先走协议侧 get_image 救，救不回才算缺失
            for (i in line.urls.size until refs.size) {
                val ref = refs[i].takeIf { it.isNotBlank() } ?: continue
                if (ref !in wanted) continue
                matched += ref
                val img = runCatching { imageSource(ref) }.getOrNull()
                    ?.let { runCatching { imageResolver.resolve(it, ref) }.getOrNull() }
                if (img != null) images += img else missing += ref
            }
        }
        // 请求了但当前窗口查无此 ref（LLM 编的标签/图已滚出窗口）→ 如实报缺失
        (wanted - matched).forEach { missing += it }
        return PromptAttachments(
            images = images.distinct().takeLast(maxPromptImages),
            missing = missing.toList().takeLast(maxPromptImages)
        )
    }

    /**
     * 地址刷新器（4xx 时调用一次）——**按可靠性排序的两级链**：
     *
     * 1. `get_image`（NapCat 协议侧自下载 → 本地路径/新鲜 URL）——不依赖直链
     *    rkey 有效期，实测 8/8 救回（见 方案记录.md 第一节），是必达路径
     * 2. `get_msg` 现取消息原文按 file 标签取新鲜 url——get_image 失败时兜底
     *
     * 返回值可以是**本地路径**（ImageResolver.download 对非 http 源直读文件）。
     *
     * @return null = 无从刷新（Bot 未连接且两条路都失败）
     */
    private fun refreshSupplier(messageId: Long?, ref: String): (() -> String?)? {
        val id = messageId
        if (id == null && lucatBot.shiroBot == null) return null
        return {
            val viaProtocol = runCatching { imageSource(ref) }.getOrNull()
            if (viaProtocol != null) {
                log.debug("图片协议侧取图成功: {}", ref)
                viaProtocol
            } else {
                try {
                    val cq = if (id != null) lucatBot.shiroBot?.getMsg(id.toInt())?.data?.message else null
                    if (cq.isNullOrBlank()) null
                    else org.dlucxoket.lucatbot.model.message.CqParser.imageUrlOf(cq, ref)
                } catch (e: Exception) {
                    log.debug("get_msg 刷新图片地址失败: {}", e.message)
                    null
                }
            }
        }
    }

    /**
     * 发送失败是否属于禁言——返回命中的 retCode（120/1200），null = 非禁言
     *
     * 只认 retCode：工具的非 ok 兜底文案恒带「（可能被禁言）」，拿文案子串
     * 当依据会把所有失败（含网络抖动）误判成禁言。
     */
    private fun bannedRetCode(detail: String): String? {
        val retCode = Regex("""retCode=(\d+)""").find(detail)?.groupValues?.get(1)
        return retCode.takeIf { it == "120" || it == "1200" }
    }

    /**
     * 发送失败通知（注入 hint，**发送成功或离开前每一轮重复出现**）：
     * 明确点出「消息没发出也没入档」，避免 LLM 误以为已发送。
     *
     * - **禁言（retCode=120/1200）**：明确**禁止再发**——实盘禁言后仍每轮试发，
     *   旧文案「可以重试/换个说法再发」在重复注入下等于每轮递刀；只留 return/stay
     * - **非禁言**：保留自主决定，但点明不要一轮接一轮连续试发
     */
    private fun buildSendFailureNote(detail: String, isGroup: Boolean): String {
        val retCode = bannedRetCode(detail)
        val banned = retCode != null
        return if (banned) {
            "上一轮你发送的消息失败了：$detail——你**被禁言了**（retCode=$retCode），禁言解除前发不出任何消息。" +
                "该消息没有发出去，也没有写入聊天记录，对方看不到。" +
                "**禁言期间不要再输出 send**：重试、换个说法都会失败，只会连番撞墙；" +
                "**直接 return 离开这个会话**——被禁言的会话里干等没意义（发不出消息也没进展），" +
                "会话列表已标【疑似禁言】，有新未读或禁言过去自然会再进来。" +
                "不要假装已经发送过；本提示在你成功发送或离开会话前每一轮都会重复。"
        } else {
            val guess = if (isGroup) "你在本群可能被禁言了，或网络/通道异常" else "发送通道可能异常（网络/对方限制）"
            "上一轮你发送的消息失败了：$detail——$guess。" +
                "该消息没有发出去，也没有写入聊天记录，对方看不到。" +
                "接下来自行决定：可以 stay 稍等后再试一次、return 离开；不要一轮接一轮连续试发；也不要假装已经发送过。"
        }
    }

    /**
     * 组装决策 prompt 的强制约束提示（可多段，换行连接）
     *
     * - 上一轮发送失败：通知 LLM 失败详情（如被禁言 retCode），由它决定去留
     * - 连续爬楼期间一直有新消息：打断爬楼，优先回应
     * - 刚 send 完且暂无新回应：按 [noReplyRounds] 递进提示——
     *   第 1 轮提示不要连发；N≥2 轮点明「已连续 N 轮行动但始终没有任何新消息」，
     *   并附上当前心情（含强度数值），由 LLM 自行判断 stay / return / 确有必要再发
     *   （无代码强制退出，去留全交智能体）
     * - 连续停留没人接话（[stayStreak]，有新消息即清零）：第 2 次点名「下一轮就走」，
     *   ≥[maxIdleStays] 次要求**本轮必须 return**；模型仍输出 stay 时编排层强制退出
     *   （实盘出过连刷 9 次 stay 空等同一个人——纯靠模型自觉走不掉）
     */
    private fun buildHint(
        readMoreWithInsertStreak: Int,
        justSent: String?,
        noReplyRounds: Int,
        newNotes: List<String>,
        sendFailureNote: String? = null,
        stayStreak: Int = 0
    ): String {
        val parts = mutableListOf<String>()
        sendFailureNote?.let { parts += it }
        if (stayStreak >= maxIdleStays - 1) {
            parts += if (stayStreak >= maxIdleStays) {
                "你已连续停留 $stayStreak 次，期间始终没有任何新消息（没人接话）——不要再干等了，" +
                    "本轮直接 return 离开会话；若继续输出 stay 会被强制退出。"
            } else {
                "你已连续停留 $stayStreak 次，期间始终没有任何新消息（没人接话）：若这轮还是没人接话，" +
                    "下一轮就直接 return 离开，别无限等下去。"
            }
        }
        if (readMoreWithInsertStreak >= maxReadMoreWithInserts) {
            parts += "有人在等你回应，优先处理新消息（send 或 return），不要再爬楼。"
        }
        if (justSent != null && newNotes.isEmpty()) {
            parts += if (noReplyRounds >= 2) {
                "你已连续 $noReplyRounds 轮行动但始终没有任何新消息（最后发送：${quoteForHint(justSent)}）。" +
                    "继续自言自语会很奇怪：可以 return 离开、stay 继续等，或确有必要再发。" +
                    "当前心情：${lucatBot.mood.toPrompt()}"
            } else {
                "你刚发送了${quoteForHint(justSent)}，暂无新回应。不要立刻连发；可用 stay 等待一会儿、return 离开，或确实有必要再发。"
            }
            // 实盘出过：停滞 12 分钟后凭记忆里的旧话题自发续话（编造对方没问过的内容）
            parts += "注意：没有新消息时不要凭记忆续话或自创话题——那不算「有必要再发」。"
            // 实盘出过 4 组逐字重复发送（MD5 全同）：模型把 justSent 引用的原文
            // 直接复制重发——必须点明「再发」的语义是新内容，不是原样再发一遍
            parts += "若确有必要再发，「再发」指的是**新内容**（追问一句、换个话题、补充说明），" +
                "绝不是把上面那句原样重发一遍——原样复制自己发过的话是复读，很奇怪。"
        }
        return parts.joinToString("\n")
    }

    private fun toConversationId(pick: SessionPick): String {
        return when (pick.conversationType) {
            SessionPickType.GROUP -> "group:${pick.targetId}"
            SessionPickType.PRIVATE, SessionPickType.PROACTIVE -> "private:${pick.targetId}"
        }
    }

    /**
     * hint 里引用「你刚发送了什么」的**截断形式**——只给前 20 字 +「…」
     *
     * 实盘（09-30）：hint 把整句原文（306 字）与历史行「我：…」同时喂给模型，
     * 弱模型顺手复制重发——群聊 3~8 秒内逐字双发 3 组、睡眠唤醒后 8 小时逐字
     * 重发 1 组（MD5 全同）。点到为止足以唤起记忆，整句喂回去就是递刀。
     * 完整原文本来就在聊天记录「我」行里，模型要复述可自行回看。
     */
    private fun quoteForHint(sent: String): String =
        if (sent.length <= 20) "「$sent」" else "「${sent.take(20)}…」"

    private fun pickName(conversationId: String): String {
        return sessionManager.getLastMessage(conversationId)?.sender
            ?: conversationId.substringAfter(":")
    }

    /**
     * 组装「当前会话」身份行（注入决策 prompt，让 LLM 知道在跟谁说话）：
     * - 群聊 = `群聊：群号 123456，群名「xxx」`（群名 [fetchGroupName] 进会话时查一次）
     * - 私聊 = `私聊：网友 QQ 123456，名字「xxx」`（名字从已读历史取最新展示名——
     *   网友改群名片/改 QQ 名后下一轮即生效；历史里还没有对方消息则只给 QQ 号）
     */
    private fun buildIdentity(conversationId: String, isGroup: Boolean, groupName: String?): String {
        val id = conversationId.substringAfter(":")
        if (isGroup) {
            return if (groupName.isNullOrBlank()) "群聊：群号 $id" else "群聊：群号 $id，群名「$groupName」"
        }
        val peerName = chatHistoryService.peerDisplayName(conversationId, id.toLongOrNull() ?: 0L)
        return if (peerName.isNullOrBlank()) "私聊：网友 QQ $id" else "私聊：网友 QQ $id，名字「$peerName」"
    }

    /**
     * 进会话时查一次群名（OneBot get_group_info，网络调用——不能每轮打）。
     * 失败/未绑定/私聊一律返回 null，身份行退化为只给群号。
     */
    private fun fetchGroupName(conversationId: String): String? {
        val groupId = conversationId.substringAfter(":").toLongOrNull() ?: return null
        return try {
            lucatBot.shiroBot?.getGroupInfo(groupId, false)?.data?.groupName
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            log.debug("查询群名失败（身份行降级为只有群号）: {}", e.message)
            null
        }
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

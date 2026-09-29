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
    /** 初始读史条数 */
    private val initialHistoryCount: Int = 10,
    /** 退出会话时记忆整理读取的聊天记录条数 */
    private val reflectHistoryCount: Int = 30,
    /** 连续 read_more 且一直有新消息插入的打断轮数 */
    private val maxReadMoreWithInserts: Int = 3,
    /** 连续发送失败上限（病态护栏：如被禁言还反复试发，达上限强制收尾） */
    private val maxSendFailures: Int = 3,
    /** 每轮 prompt 的图片附件上限（请求体体积闸；取最近的几张） */
    private val maxPromptImages: Int = 6
) {
    private val log = LoggerFactory.getLogger(ConversationController::class.java)

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
        reflectHistoryCount = reflectHistoryCount
    )

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
            "进入${if (isGroup) "群聊" else "私聊"} ${pick.targetId}" + if (proactive) "（主动找人）" else ""
        )

        // 进入会话即消费未读并标已读
        lucatBot.consumeUnread(conversationId)

        try {
            loop(conversationId, isGroup, proactive)
            // loop() 正常返回（含内部 return）→ 退出前整理一次记忆；
            // loop() 抛异常则跳过整理，直接走 finally
            reflector.reflect(conversationId)
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
        // 发送失败状态（注入下一轮 prompt 通知 LLM，由它决定去留；连续失败达上限强制收尾）
        var sendFailureNote: String? = null
        var sendFailStreak = 0
        // 工具上下文（本会话内累积：状态计数/目录/执行结果，随每轮注入 prompt）
        val toolNotes = ToolNotes(maxTotalChars = 16_000, hardLimitSteps = 12)
        // 会话身份：群名进会话时查一次（网络调用不每轮打）；私聊每轮现算（见 buildIdentity）
        val cachedGroupName = if (isGroup) fetchGroupName(conversationId) else null
        // 机器人自己的 QQ 号（LLM 判断「@的是不是我」的依据；未连接 → 0）
        val selfQq = lucatBot.shiroBot?.selfId ?: 0L

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

            // 图片通道：窗口记录 + 本轮新消息 → data URI 附件 + 「未能附上」清单
            // （上限内取最近的；同址缓存、4xx 负缓存、过期地址用 get_msg 刷新重试一次
            //  ——QQ 图片地址短效；附件失败绝不影响会话链路。
            //  缺失清单把附件失败的事实如实交给 LLM——防「一张没收到却装看过图」的幻觉）
            val attachments = gatherPromptImages(
                historyResult.messages.map { ImageLine(it.content, it.imageUrls.orEmpty(), it.messageId) } +
                    inserts.map { ImageLine(it.content, it.imageUrls.orEmpty(), it.messageId) }
            )

            // 3) 组请求并决策（带爬楼状态，由 LLM 自行决定继续/回复/离开）
            val reachedHistoryStart = historyResult.messages.size < historyCount
            val request = ConversationRequest(
                persona = lucatBot.persona,
                mood = lucatBot.mood,
                timeHint = TimeAwareness.timeHint(),
                conversationType = if (isGroup) "群聊" else "私聊",
                conversationName = pickName(conversationId),
                conversationIdentity = buildIdentity(conversationId, isGroup, cachedGroupName),
                conversationId = conversationId,
                selfQq = selfQq,
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
                hint = buildHint(readMoreWithInsertStreak, justSent, noReplyRounds, newNotes, sendFailureNote),
                toolNotes = toolNotes.snapshot(),
                images = attachments.images,
                missingImages = attachments.missing
            )

            var decision = conversationAgent.processObject(request)
            // send 动作的 remember 延后到发送成功才写（防「没发出去却记了『我回复了』」）
            val deferredRemember = applyDecisionSideEffects(decision, conversationId)

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
                        // 失败消息不入档（SendMessageTool 仅 ok 后写记录；短期记忆/正则提取
                        // 也只走成功分支）——LLM 下一轮看到失败通知后自行决定去留
                        sendFailStreak++
                        val detail = sendResult.error.orEmpty()
                        log.warn("发送失败: {}", detail)
                        actionLog.append("发送失败")
                        sendFailureNote = buildSendFailureNote(detail, isGroup)
                        mustIcebreak = false // 破冰已尝试——失败后去留交给 LLM，不许再强制重发
                        if (deferredRemember != null) {
                            // 没发出去就不能记「我回复了…」——丢弃（LLM 可下轮重新 decide）
                            log.info("发送失败，丢弃随发送写入的记忆: {}", deferredRemember)
                        }
                        if (sendFailStreak >= maxSendFailures) {
                            // 病态护栏：连续失败达上限（如一直被禁言还反复试发）强制收尾
                            actionLog.append("连续发送失败 $sendFailStreak 次，返回消息列表")
                            log.warn("连续发送失败 {} 次，强制离开会话", sendFailStreak)
                            return
                        }
                    } else {
                        sendFailStreak = 0
                        sendFailureNote = null
                        // 发送成功 → 才写延后的 remember（「我回复了xxx」此时才属实）
                        deferredRemember?.let { writeRemember(it, conversationId) }
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
     * 处理决策信封的副作用字段（在执行 action 之前）
     *
     * todo_add / todo_done → 待办清单；remember → 短期记忆，但
     * **send 动作的 remember 延后到发送成功后才写**（返回值即待写的 remember）——
     * 否则 LLM 边发边记的「我回复了 xxx」会在发送失败（如被禁言）时留下假记忆。
     *
     * 每项单独 try/catch：失败只 log，不中断主循环；
     * 成功与失败都追加一行操作日志（记住 / 待办+ / 待办完成 / 写入失败）。
     *
     * @return 被延后的 remember（非 send 动作或无 remember → null，已即时写入）
     */
    private fun applyDecisionSideEffects(decision: ConversationDecision, conversationId: String): String? {
        val deferredRemember = decision.remember?.takeIf { decision.action == ConversationAction.SEND }
        decision.remember
            ?.takeIf { decision.action != ConversationAction.SEND }
            ?.let { writeRemember(it, conversationId) }
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
        return deferredRemember
    }

    /** 写一条短期记忆 + 操作日志（失败只 log，不中断主循环） */
    private fun writeRemember(note: String, conversationId: String) {
        try {
            memoryService.rememberEvent(MemorySubject.fromConversation(conversationId), note)
            actionLog.append("记住：$note")
        } catch (e: Exception) {
            log.warn("remember 写入失败: {}", e.message)
            actionLog.append("记忆写入失败")
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
     * 收集本轮 prompt 的图片附件（图片通道）
     *
     * @param sources 每条消息的正文 + 图片地址——地址与正文里
     *   `[图片 file=x]` 占位符按下标对应（入档时同序收集）
     *
     * 规则：逐条解析（同地址缓存复用、拿不到就跳过），整体去重后
     * **只取最近的 [maxPromptImages] 张**；拿不到的（含旧数据无地址的
     * 占位符）进「未能附上」清单——清单进 prompt，LLM 才不会把
     * 没收到的图当成看过（实盘出过：零附件却声称「盯着图看了半天」）。
     */
    private fun gatherPromptImages(sources: List<ImageLine>): PromptAttachments {
        val images = mutableListOf<org.dlucxoket.lucatbot.bot.llm.PromptImage>()
        val missing = linkedSetOf<String>()
        for (line in sources) {
            val refs = org.dlucxoket.lucatbot.model.message.AssetMarkers.refs(line.content)
            line.urls.forEachIndexed { i, url ->
                val ref = refs.getOrNull(i)?.takeIf { it.isNotBlank() }
                    ?: url.substringAfterLast('/').substringBefore('?')
                val img = imageResolver.resolve(url, ref, refreshSupplier(line.messageId, ref))
                if (img != null) images += img else missing += ref
            }
            // 占位符有、地址没有（旧数据/收集不全）→ 也算未能附上
            for (i in line.urls.size until refs.size) {
                refs[i].takeIf { it.isNotBlank() }?.let { missing += it }
            }
        }
        return PromptAttachments(
            images = images.distinct().takeLast(maxPromptImages),
            missing = missing.toList().takeLast(maxPromptImages)
        )
    }

    /**
     * 地址刷新器（4xx 时调用一次）：`get_msg` 现取消息原文，
     * 按 file 标签取**新鲜 url**——QQ 图片地址短效（rkey 过期即
     * `download url has expired`），只有现场重取才可能还在有效期内。
     *
     * @return null = 无从刷新（无 message_id / Bot 未连接）
     */
    private fun refreshSupplier(messageId: Long?, ref: String): (() -> String?)? {
        val id = messageId ?: return null
        val b = lucatBot.shiroBot ?: return null
        return {
            try {
                val cq = b.getMsg(id.toInt())?.data?.message
                if (cq.isNullOrBlank()) null
                else org.dlucxoket.lucatbot.model.message.CqParser.imageUrlOf(cq, ref)
            } catch (e: Exception) {
                log.debug("get_msg 刷新图片地址失败: {}", e.message)
                null
            }
        }
    }

    /**
     * 发送失败通知（注入下一轮 hint，让 LLM 知道失败原因并自行决定去留）：
     * 带上原始错误（status/retCode，如被禁言 1200）+ 现场提示 + 行为选项；
     * 明确点出「消息没发出也没入档」，避免 LLM 误以为已发送。
     */
    private fun buildSendFailureNote(detail: String, isGroup: Boolean): String {
        val guess = if (isGroup) "你在本群可能被禁言了" else "发送通道可能异常（网络/对方限制）"
        return "上一轮你发送的消息失败了：$detail——$guess。" +
            "该消息没有发出去，也没有写入聊天记录，对方看不到。" +
            "接下来自行决定：可以 stay 等一会儿重试、return 离开、或换个说法再发；不要假装已经发送过。"
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
     */
    private fun buildHint(
        readMoreWithInsertStreak: Int,
        justSent: String?,
        noReplyRounds: Int,
        newNotes: List<String>,
        sendFailureNote: String? = null
    ): String {
        val parts = mutableListOf<String>()
        sendFailureNote?.let { parts += it }
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
            // 实盘出过：停滞 12 分钟后凭记忆里的旧话题自发续话（编造对方没问过的内容）
            parts += "注意：没有新消息时不要凭记忆续话或自创话题——那不算「有必要再发」。"
        }
        return parts.joinToString("\n")
    }

    private fun toConversationId(pick: SessionPick): String {        return when (pick.conversationType) {
            SessionPickType.GROUP -> "group:${pick.targetId}"
            SessionPickType.PRIVATE, SessionPickType.PROACTIVE -> "private:${pick.targetId}"
        }
    }

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

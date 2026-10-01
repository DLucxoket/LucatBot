package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.NotificationAgent
import org.dlucxoket.lucatbot.bot.agent.data.NotificationAction
import org.dlucxoket.lucatbot.bot.agent.data.NotificationDecision
import org.dlucxoket.lucatbot.bot.agent.data.NotificationRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.model.event.message.MessageEvent
import org.dlucxoket.lucatbot.model.session.ConversationId
import org.slf4j.LoggerFactory

/**
 * 通知通道编排——外来私聊 / @我 / @全体 消息到达时的「手机横幅」处理
 *
 * **取代旧路径**「@我 → notifyUrgent 缩短空闲休眠」：通知不再唤醒主循环，
 * 由 [NotificationAgent] 当场三选一：
 * - [NotificationAction.IGNORE] 划掉（消息已入档+进未读，之后扫列表仍可见）
 * - [NotificationAction.TODO] 记成待办稍后处理
 * - [NotificationAction.OPEN] **当场跳转**——经 [MainLoopController.tryRunSessionExclusive]
 *   拿会话执行锁直接跑 [ConversationController]（主循环忙则降级入未读，
 *   下轮 pick 高优先级看到）
 *
 * 处于聊天界面时附带当前聊天记录（判断打断价值：当前对话正热 → 倾向不切走）。
 * 在 Shiro 消息线程同步执行（一次 LLM 判断，与现有消息处理同线程语义）。
 */
class NotificationDispatcher(
    private val lucatBot: LucatBot,
    private val conversationController: ConversationController,
    private val mainLoopController: MainLoopController,
    private val notificationAgent: NotificationAgent = NotificationAgent(),
    private val todoService: TodoService,
    private val chatHistoryService: ChatHistoryService,
    private val memoryService: MemoryService,
    private val actionLog: ActionLog,
    /** 处于聊天界面时附带的当前聊天记录条数（判断打断价值用） */
    private val currentChatLines: Int = 20,
    /** 跳转等锁上限（请求让位后等会话循环让出；测试传短值避免真等） */
    private val gateWaitMs: Long = 15_000L
) {
    private val log = LoggerFactory.getLogger(NotificationDispatcher::class.java)

    /**
     * 处理一条外来通知（MainPlugin 的 NOTIFY 分支调用；消息此时已入档）
     *
     * 任何异常只 log——通知处理绝不影响消息接收主链路。
     */
    fun handle(modelEvent: MessageEvent, content: String, isAtMe: Boolean, isAtAll: Boolean) {
        try {
            val convId = modelEvent.conversationId
            val cid = ConversationId.parse(convId)
            val isGroup = cid.isGroup
            val targetId = cid.targetId
            val senderName = modelEvent.sender.displayName

            // 处于聊天界面 → 附当前会话的聊天记录（外来源通知打断前的现场）
            val current = lucatBot.state as? LucatBot.State.InConversation
            val currentChat = current?.conversationId
                ?.let { chatHistoryService.getHistorySummary(it, currentChatLines) }
                .orEmpty()
            val stateDescription = when {
                current != null -> "正在聊天中（会话：${current.conversationId}）"
                lucatBot.state is LucatBot.State.ViewingConversations -> "正在看消息列表"
                else -> "空闲（没在用 QQ）"
            }

            val request = NotificationRequest(
                persona = lucatBot.persona,
                mood = lucatBot.mood,
                timeHint = TimeAwareness.timeHint(),
                stateDescription = stateDescription,
                currentChat = currentChat,
                notifyConversationType = if (isGroup) "群聊" else "私聊",
                notifyConversationId = targetId,
                notifyConversationName = conversationLabel(convId, modelEvent.sender.qqNumber),
                senderName = senderName,
                senderId = modelEvent.sender.qqNumber.toString(),
                messageContent = content,
                isAtAll = isAtAll,
                isAtMe = isAtMe,
                todos = todoService.summaryForPrompt(),
                shortTermMemory = memoryService.getMemoryOverview(primary = null)
            )

            val decision = notificationAgent.processObject(request)
            when (decision.action) {
                NotificationAction.IGNORE -> {
                    log.info("通知无视：{}（{}）—— {}", senderName, convId, decision.reason ?: "无说明")
                    actionLog.append("通知：无视 ${senderName}(${targetId})", decision.reason, convId)
                }

                NotificationAction.TODO -> {
                    val todoContent = decision.todoContent
                    if (todoContent == null) {
                        log.warn("通知转待办缺 content，降级无视")
                        actionLog.append("通知：无视 ${senderName}(${targetId})", conversationId = convId)
                    } else {
                        todoService.add(todoContent, convId)
                        log.info("通知转待办：{}", todoContent)
                        actionLog.append("通知转待办：$todoContent", decision.reason, convId)
                    }
                }

                NotificationAction.OPEN -> {
                    val pick = SessionPick(
                        conversationType = if (isGroup) SessionPickType.GROUP else SessionPickType.PRIVATE,
                        targetId = targetId
                    )
                    val tookOver = mainLoopController.tryRunSessionExclusive(gateWaitMs) {
                        log.info("通知当场跳转：{}（{}）", senderName, convId)
                        actionLog.append("通知点开：${senderName}(${targetId})", decision.reason, convId)
                        conversationController.run(pick)
                    }
                    if (!tookOver) {
                        // 主循环正跑另一会话/选会话——不并发抢：消息已在未读队列，
                        // 下轮 pick（该会话有新未读/@我）优先级天然很高
                        log.info("通知判跳转但主循环正忙，入未读排队（下轮高优先级）")
                        actionLog.append("通知点开排队（主循环正忙）：${senderName}(${targetId})", conversationId = convId)
                    }
                }
            }
        } catch (e: Exception) {
            log.error("通知处理异常（忽略，不影响消息接收）: {}", e.message, e)
        }
    }

    /** 会话展示名（给 LLM 看；群取不到名时留空串，私聊取对方昵称） */
    private fun conversationLabel(conversationId: String, peerId: Long): String {
        if (ConversationId.parse(conversationId).isGroup) return ""
        return chatHistoryService.peerDisplayName(conversationId, peerId).orEmpty()
    }
}

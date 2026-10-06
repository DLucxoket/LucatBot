package org.dlucxoket.lucatbot.adapter.onebot

import com.mikuac.shiro.core.Bot
import com.mikuac.shiro.core.BotPlugin
import org.dlucxoket.lucatbot.bot.llm.TokenUsageScope
import com.mikuac.shiro.dto.event.message.AnyMessageEvent
import com.mikuac.shiro.dto.event.request.FriendAddRequestEvent
import com.mikuac.shiro.enums.MsgTypeEnum
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationAction
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.MainLoopController
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.tool.ToolDependencyBinder
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.session.ConversationId
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Shiro 主插件——OneBot 消息处理入口
 *
 * 职责：协议适配 + 事件喂给状态机（不包含业务决策）
 * - 消息转换（ShiroConverter）
 * - 未读/待插入队列维护（LucatBot）
 * - 主循环启动与空闲唤醒信号（MainLoopController）
 *
 * 主路径的状态机编排在 [MainLoopController] / ConversationController / MessageListController；
 * 戳一戳等旁路事件直接构造请求调用 [ConversationAgent]，无自由调度链路。
 */
@Component
internal class MainPlugin: BotPlugin() {
    private val log = LoggerFactory.getLogger(MainPlugin::class.java)

    @Autowired
    private lateinit var lucatBot: LucatBot

    @Autowired
    private lateinit var sessionManager: SessionManager

    @Autowired
    private lateinit var chatHistoryService: ChatHistoryService

    @Autowired
    private lateinit var mainLoopController: MainLoopController

    @Autowired
    private lateinit var actionLog: ActionLog

    /** 通知通道（外来私聊/@ 的横幅决策；取代旧的唤醒主循环路径） */
    @Autowired
    private lateinit var notificationDispatcher: org.dlucxoket.lucatbot.bot.loop.NotificationDispatcher

    /** 已读水位——首条消息（selfId 已知）时按水位重建重启前的未读 */
    @Autowired
    private lateinit var readStateService: org.dlucxoket.lucatbot.bot.readstate.ReadStateService

    /** 待处理请求队列（群邀请/加群申请/好友请求）——入队后由 LLM 经 Tool 审批 */
    @Autowired
    private lateinit var pendingRequests: org.dlucxoket.lucatbot.bot.request.PendingRequestStore

    /** 戳一戳旁路直接用的会话内决策智能体 */
    private val conversationAgent = ConversationAgent()

    override fun onAnyMessage(bot: Bot, event: AnyMessageEvent): Int {
        try {
            // 保存 Bot 引用供发送工具/主动调度使用
            lucatBot.shiroBot = bot
            bindTools(bot)
            mainLoopController.startIfNeeded()
            // 重启后首次拿到 selfId：按已读水位把重启前没看的消息灌回未读（幂等）
            readStateService.rebuildUnread(lucatBot, bot.selfId)

            // 戳一戳检测（模拟真人被戳了会戳回去）
            if (isPoke(event)) {
                handlePoke(bot, event)
                return MESSAGE_BLOCK
            }

            val modelEvent = ShiroConverter.toModelEvent(event)
            // 富内容：@/reply/图片/表情/表情包都要让 LLM 看到，不能再用纯文本
            val content = ShiroConverter.richContent(event)
            // 到达即预取已停用：实盘 QQ 图片地址到达时 rkey 已失效（预取 100% 400），
            // 图片统一由决策轮取材——主地址 4xx 后走 get_image 协议侧救援（不依赖过期直链）
            val chatMessage = modelEvent.message as? ChatMessage
            // 纯空消息才忽略；纯图片/@/表情等非文本段也要处理
            if (content.isBlank() && chatMessage?.hasNonTextContent() != true) {
                return MESSAGE_IGNORE
            }

            // 状态机决策：进未读 / 待插入 / 通知
            val isAtMe = isAtMe(event, bot.selfId)
            val isAtAll = chatMessage?.isAtAll() ?: false
            val action = lucatBot.handleMessage(modelEvent, isAtMe, isAtAll)

            // 无论是否立刻处理，都先记聊天记录与短期记忆
            chatHistoryService.recordMessage(modelEvent, content)
            val convId = modelEvent.conversationId
            sessionManager.addMessage(
                convId, modelEvent.sender.displayName, content,
                senderId = modelEvent.sender.qqNumber
            )

            when (action) {
                LucatBot.MessageAction.IGNORE -> return MESSAGE_IGNORE
                LucatBot.MessageAction.NOTIFY -> {
                    // 通知通道（取代旧「@我 → 唤醒主循环」）：NotificationAgent
                    // 当场判 无视 / 建待办 / 跳转会话；消息已入档+进未读
                    log.debug("外来私聊/@ 消息，转入通知通道")
                    notificationDispatcher.handle(modelEvent, content, isAtMe, isAtAll)
                }
                LucatBot.MessageAction.PROCESS -> {
                    // 当前会话消息已进待插入队列，由会话循环在决策间隙合并
                    log.debug("当前会话消息已入待插入队列")
                }
            }

            return MESSAGE_IGNORE
        } catch (e: Exception) {
            log.error("消息处理异常: ${e.message}", e)
            return MESSAGE_IGNORE
        }
    }

    override fun onFriendAddRequest(bot: Bot, event: FriendAddRequestEvent): Int {
        // 旧版硬编码无条件自动同意（机器人对「谁加好友」零感知）；改为
        // 入队 + 记操作日志，由 LLM 看验证消息后经 FriendRequestTool 决策
        val pending = pendingRequests.add(
            kind = org.dlucxoket.lucatbot.bot.request.PendingRequestStore.Kind.FRIEND,
            flag = event.flag,
            userId = event.userId,
            comment = event.comment
        )
        actionLog.append("收到好友请求：QQ ${event.userId}（${event.comment ?: "无验证消息"}）→ 待审批 #${pending.seq}")
        log.info("好友请求入队 #{}：QQ {}（{}）", pending.seq, event.userId, event.comment)
        // 缩短空闲休眠：待审批请求值得尽快看（LLM 下一轮列表即见操作日志）
        mainLoopController.notifyUrgent()
        return super.onFriendAddRequest(bot, event)
    }

    /**
     * 收到入群邀请 / 加群申请（此前该回调未实现——事件被静默忽略）
     *
     * subType：`invite` = 机器人被邀请进群；`add` = 有人申请加入机器人管理的群。
     * 两类都入队 + 记日志，由 LLM 经 [org.dlucxoket.lucatbot.bot.tool.GroupRequestTool]
     * 决策同意/拒绝（与好友请求同一条「模拟真人审批」链路）。
     */
    override fun onGroupAddRequest(bot: Bot, event: com.mikuac.shiro.dto.event.request.GroupAddRequestEvent): Int {
        val invite = event.subType.equals("invite", ignoreCase = true)
        val kind = if (invite) {
            org.dlucxoket.lucatbot.bot.request.PendingRequestStore.Kind.GROUP_INVITE
        } else {
            org.dlucxoket.lucatbot.bot.request.PendingRequestStore.Kind.GROUP_JOIN
        }
        val pending = pendingRequests.add(
            kind = kind,
            flag = event.flag,
            subType = event.subType,
            groupId = event.groupId,
            userId = event.userId,
            invitorId = event.invitorId,
            comment = event.comment
        )
        val label = if (invite) "入群邀请" else "加群申请"
        actionLog.append("收到${label}：群 ${event.groupId}（${if (invite) "邀请人" else "申请人"} ${event.userId}）→ 待审批 #${pending.seq}")
        log.info("{}入队 #{}：群 {} 用户 {} subType={}", label, pending.seq, event.groupId, event.userId, event.subType)
        mainLoopController.notifyUrgent()
        return super.onGroupAddRequest(bot, event)
    }

    /**
     * 判断是否戳一戳消息
     */
    private fun isPoke(event: AnyMessageEvent): Boolean {
        val segments = event.arrayMsg ?: return false
        return segments.any { seg ->
            seg.type == MsgTypeEnum.poke ||
                seg.getStringData("type") == "poke"
        }
    }

    /**
     * 处理戳一戳（ConversationAgent 决策：回应一句/不回应）
     *
     * 模拟真人的反应：看心情和场景决定怎么回应。
     */
    private fun handlePoke(bot: Bot, event: AnyMessageEvent) {
        try {
            log.info("被戳一戳")

            // 会话键拼装收口 ConversationId（协议唯一拼装点；本方法复用 6 次）
            val pokeConvId = ConversationId.private(event.userId).asKey()
            val request = ConversationRequest(
                persona = lucatBot.persona,
                mood = lucatBot.mood,
                timeHint = TimeAwareness.timeHint(),
                conversationType = "私聊",
                conversationName = event.userId.toString(),
                conversationIdentity = "私聊：网友 QQ ${event.userId}" + (
                    chatHistoryService.peerDisplayName(pokeConvId, event.userId)
                        ?.let { "，名字「$it」" } ?: ""
                    ),
                conversationId = pokeConvId,
                selfQq = bot.selfId,
                newMessageNotes = listOf("（戳一戳）QQ号 ${event.userId} 戳了你一下"),
                hint = "这是戳一戳场景：想回应就 send 一句短的（如「干嘛」「戳我干嘛」），不想理就 return"
            )

            // token 统计维度：戳一戳这轮调用归到该私聊
            val decision = TokenUsageScope.withConversation(pokeConvId) {
                conversationAgent.processObject(request)
            }

            if (decision.action == ConversationAction.SEND) {
                val content = decision.content.orEmpty()
                val resp = bot.sendMsg(event, content, false)
                if (resp?.status == "ok") {
                    // 回执 message_id 入档：网友回复这条戳一戳回应时引用可解析。
                    // data 是 MsgId 对象（as? Number 曾恒为 null → 全部 UUID 兜底行）
                    val receiptId = (resp.data as? com.mikuac.shiro.dto.action.common.MsgId)?.messageId?.toLong()
                        ?: (resp.data as? Number)?.toLong()
                    chatHistoryService.recordBotReply(pokeConvId, content, receiptId)
                    sessionManager.addMessage(pokeConvId, lucatBot.persona.name, content, isBot = true)
                    actionLog.append("戳一戳回应：$content", conversationId = pokeConvId)
                    log.info("戳一戳回应: {}", content)
                } else {
                    // 被禁言等发送失败（Shiro 已打 ERROR 日志）——不写记录，直接返回
                    log.warn("戳一戳回应发送失败 status={} retCode={}（可能被禁言）", resp?.status, resp?.retCode)
                }
            } else {
                actionLog.append("戳一戳：不回应", conversationId = pokeConvId)
                log.info("戳一戳：不回应")
            }
        } catch (e: Exception) {
            log.error("戳一戳处理异常: ${e.message}")
        }
    }

    private fun bindTools(bot: Bot) {
        ToolDependencyBinder.bindBotToAll(bot)
    }

    /**
     * 判断消息是否 @了机器人（Shiro DTO 层判断）
     */
    private fun isAtMe(event: AnyMessageEvent, selfId: Long): Boolean {
        val segments = event.arrayMsg ?: return false
        return segments.any { seg ->
            seg.type == MsgTypeEnum.at &&
                seg.getStringData("qq")?.toLongOrNull() == selfId
        }
    }
}

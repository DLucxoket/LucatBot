package org.dlucxoket.lucatbot.adapter.onebot

import com.mikuac.shiro.core.Bot
import com.mikuac.shiro.core.BotPlugin
import com.mikuac.shiro.dto.event.message.AnyMessageEvent
import com.mikuac.shiro.dto.event.request.FriendAddRequestEvent
import com.mikuac.shiro.enums.MsgTypeEnum
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.AgentChain
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.tool.ChatHistoryTool
import org.dlucxoket.lucatbot.bot.tool.MessageListTool
import org.dlucxoket.lucatbot.bot.tool.UserProfileTool
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.member.AtMessageMember
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Shiro 主插件——OneBot 消息处理入口
 *
 * 消息处理链路（模拟真人行为）：
 * 1. ShiroConverter 转为 model 层对象
 * 2. LucatBot 状态机判断消息该怎么处理（PROCESS/NOTIFY/IGNORE）
 * 3. PROCESS/NOTIFY → AgentChain 调度 → 回复
 * 4. IGNORE → 加入未读队列，不处理
 */
@Component
internal class MainPlugin: BotPlugin() {
    private val log = LoggerFactory.getLogger(MainPlugin::class.java)

    /** Agent 调度执行引擎（Spring 注入） */
    @Autowired
    private lateinit var agentChain: AgentChain

    /** 机器人状态机（Spring 注入，全局共享） */
    @Autowired
    private lateinit var lucatBot: LucatBot

    /** 会话历史管理 */
    @Autowired
    private lateinit var sessionManager: SessionManager

    override fun onAnyMessage(bot: Bot, event: AnyMessageEvent): Int {
        try {
            bindTools(bot)

            val modelEvent = ShiroConverter.toModelEvent(event)
            val plainText = ShiroConverter.extractPlainText(event)
            if (plainText.isBlank()) {
                return MESSAGE_IGNORE
            }

            // 判断是否 @我 或 @全体
            val isAtMe = isAtMe(event, bot.selfId)
            val isAtAll = isAtAll(modelEvent)

            // LucatBot 状态机决策
            val action = lucatBot.handleMessage(modelEvent, isAtMe, isAtAll)

            when (action) {
                LucatBot.MessageAction.IGNORE -> {
                    log.info("消息进未读队列（状态：${lucatBot.state}）")
                    return MESSAGE_IGNORE
                }
                LucatBot.MessageAction.NOTIFY -> {
                    log.info("收到 @我/@全体 消息，处理中...")
                }
                LucatBot.MessageAction.PROCESS -> {
                    log.info("当前会话消息，处理中...")
                }
            }

            // 执行 Agent 调度链
            log.info("收到消息: $plainText")

            // 记录到会话历史
            val convId = buildConversationId(modelEvent)
            sessionManager.addMessage(convId, modelEvent.sender.nickname, plainText)

            // 获取会话上下文（最近的历史）
            val history = sessionManager.getContextSummary(convId)

            // 打字延迟（模拟真人不秒回）
            if (TimeAwareness.shouldDelay()) {
                val delay = TimeAwareness.typingDelay(plainText.length)
                log.info("模拟打字延迟 ${delay}ms")
                Thread.sleep(delay)
            }

            // 构建带历史的上下文
            val contextWithHistory = """
                当前消息：$plainText

                聊天记录：
                $history
            """.trimIndent()

            val reply = agentChain.execute(contextWithHistory)

            if (reply.isNotBlank()) {
                // 记录机器人的回复到历史
                sessionManager.addMessage(convId, lucatBot.persona.name, reply, isBot = true)
                bot.sendMsg(event, reply, false)
                log.info("回复消息: $reply")
            } else {
                log.info("AgentChain 返回空回复，跳过发送")
            }

            return MESSAGE_BLOCK
        } catch (e: Exception) {
            log.error("消息处理异常: ${e.message}", e)
            return MESSAGE_IGNORE
        }
    }

    override fun onFriendAddRequest(bot: Bot, event: FriendAddRequestEvent): Int {
        bot.setFriendAddRequest(event.flag, true, null)
        return super.onFriendAddRequest(bot, event)
    }

    private fun bindTools(bot: Bot) {
        ChatHistoryTool.bindBot(bot)
        UserProfileTool.bindBot(bot)
        MessageListTool.bindBot(bot)
    }

    /**
     * 构建会话 ID
     */
    private fun buildConversationId(event: org.dlucxoket.lucatbot.model.event.message.MessageEvent): String {
        return if (event is org.dlucxoket.lucatbot.model.event.message.GroupMessageEvent) {
            "group:${event.group.groupNumber}"
        } else {
            "private:${event.sender.qqNumber}"
        }
    }

    /**
     * 判断消息是否 @了机器人
     */
    private fun isAtMe(event: AnyMessageEvent, selfId: Long): Boolean {
        val segments = event.arrayMsg ?: return false
        return segments.any { seg ->
            seg.type == MsgTypeEnum.at &&
                seg.getStringData("qq")?.toLongOrNull() == selfId
        }
    }

    /**
     * 判断消息是否 @全体成员
     */
    private fun isAtAll(event: org.dlucxoket.lucatbot.model.event.message.MessageEvent): Boolean {
        val msg = event.message
        if (msg is ChatMessage) {
            return msg.messageMembers.any {
                it is AtMessageMember && (it.targetQqNumber == -1L || it.targetQqNumber == 0L)
            }
        }
        return false
    }
}

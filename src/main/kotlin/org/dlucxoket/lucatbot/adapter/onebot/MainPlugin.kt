package org.dlucxoket.lucatbot.adapter.onebot

import com.mikuac.shiro.core.Bot
import com.mikuac.shiro.core.BotPlugin
import com.mikuac.shiro.dto.event.message.AnyMessageEvent
import com.mikuac.shiro.dto.event.request.FriendAddRequestEvent
import com.mikuac.shiro.enums.MsgTypeEnum
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.AgentChain
import org.dlucxoket.lucatbot.bot.memory.MemoryExtractor
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.bot.tool.ChatHistoryTool
import org.dlucxoket.lucatbot.bot.tool.MessageListTool
import org.dlucxoket.lucatbot.bot.tool.UserProfileTool
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Shiro 主插件——OneBot 消息处理入口
 *
 * 职责：协议适配 + 流程编排（不包含具体业务逻辑）
 * - 消息转换（ShiroConverter）
 * - 状态机决策（LucatBot）
 * - 调度执行（AgentChain）
 * - 回复发送
 *
 * 具体逻辑委托给专职类：
 * - 拆分回复 → [ReplySplitter]
 * - 记忆提取 → [MemoryExtractor]
 * - @判断 → [isAtMe]（Shiro 层）
 */
@Component
internal class MainPlugin: BotPlugin() {
    private val log = LoggerFactory.getLogger(MainPlugin::class.java)

    @Autowired
    private lateinit var agentChain: AgentChain

    @Autowired
    private lateinit var lucatBot: LucatBot

    @Autowired
    private lateinit var sessionManager: SessionManager

    @Autowired
    private lateinit var memoryService: MemoryService

    override fun onAnyMessage(bot: Bot, event: AnyMessageEvent): Int {
        try {
            bindTools(bot)

            val modelEvent = ShiroConverter.toModelEvent(event)
            val plainText = ShiroConverter.extractPlainText(event)
            if (plainText.isBlank()) return MESSAGE_IGNORE

            // 状态机决策
            val isAtMe = isAtMe(event, bot.selfId)
            val isAtAll = (modelEvent.message as? ChatMessage)?.isAtAll() ?: false
            val action = lucatBot.handleMessage(modelEvent, isAtMe, isAtAll)

            when (action) {
                LucatBot.MessageAction.IGNORE -> {
                    log.info("消息进未读队列（状态：${lucatBot.state}）")
                    return MESSAGE_IGNORE
                }
                LucatBot.MessageAction.NOTIFY -> log.info("收到 @我/@全体 消息，处理中...")
                LucatBot.MessageAction.PROCESS -> log.info("当前会话消息，处理中...")
            }

            log.info("收到消息: $plainText")

            // 会话历史 + 记忆
            val convId = modelEvent.conversationId
            sessionManager.addMessage(convId, modelEvent.sender.nickname, plainText)

            // 打字延迟
            if (TimeAwareness.shouldDelay()) {
                val delay = TimeAwareness.typingDelay(plainText.length)
                log.info("模拟打字延迟 ${delay}ms")
                Thread.sleep(delay)
            }

            // 构建上下文（历史 + 记忆 + 疲劳）
            val context = buildContext(convId, modelEvent.sender.qqNumber, plainText)
            val reply = agentChain.execute(context)

            if (reply.isNotBlank()) {
                sessionManager.addMessage(convId, lucatBot.persona.name, reply, isBot = true)

                // 记忆提取
                val memories = MemoryExtractor.extract(modelEvent.sender.qqNumber, plainText, reply)
                memoryService.saveAll(memories)

                // 拆分发送
                val messages = ReplySplitter.split(reply)
                for ((i, msg) in messages.withIndex()) {
                    bot.sendMsg(event, msg, false)
                    if (i < messages.size - 1) Thread.sleep((300..800).random().toLong())
                }
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

    /**
     * 构建回复上下文
     */
    private fun buildContext(convId: String, userId: Long, plainText: String): String {
        val history = sessionManager.getContextSummary(convId)
        val memorySummary = memoryService.getMemorySummary(userId)
        val fatigueHint = sessionManager.getFatigueHint(convId)

        return """
            当前消息：$plainText

            聊天记录：
            $history

            $memorySummary
            $fatigueHint
        """.trimIndent()
    }

    private fun bindTools(bot: Bot) {
        ChatHistoryTool.bindBot(bot)
        UserProfileTool.bindBot(bot)
        MessageListTool.bindBot(bot)
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

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

            // 戳一戳检测（模拟真人被戳了会戳回去）
            if (isPoke(event)) {
                handlePoke(bot, event)
                return MESSAGE_BLOCK
            }

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

            // 构建上下文——用 describe() 让 LLM 看到完整消息（含图片/表情）
            val fullDescription = (modelEvent.message as? ChatMessage)?.describe() ?: plainText
            val context = buildContext(convId, modelEvent.sender.qqNumber, fullDescription)
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
     * 判断是否戳一戳消息
     */
    private fun isPoke(event: AnyMessageEvent): Boolean {
        // OneBot 戳一戳通常是 type=poke 的事件或消息中包含 poke 标记
        val segments = event.arrayMsg ?: return false
        return segments.any { seg ->
            seg.type.toString().equals("poke", ignoreCase = true) ||
                seg.getStringData("type") == "poke"
        }
    }

    /**
     * 处理戳一戳（Agent 决策：戳回去/不理/问一句）
     *
     * 模拟真人的反应：看心情和关系决定怎么回应。
     */
    private fun handlePoke(bot: Bot, event: AnyMessageEvent) {
        try {
            log.info("被戳一戳")

            val context = """
                有人戳了我一下（QQ戳一戳）。
                发起者：QQ号 ${event.userId}
                当前心情：${lucatBot.mood.toPrompt()}

                像真人一样决定怎么反应，可能的反应：
                - 戳回去
                - 不理（懒得回）
                - 回一句"干嘛"或类似的话
                - 回个表情

                根据心情和场景自然选择。
            """.trimIndent()

            val reply = agentChain.execute(context)

            if (reply.isNotBlank()) {
                bot.sendMsg(event, reply, false)
                log.info("戳一戳回应: $reply")
            } else {
                log.info("戳一戳：不回应")
            }
        } catch (e: Exception) {
            log.error("戳一戳处理异常: ${e.message}")
        }
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

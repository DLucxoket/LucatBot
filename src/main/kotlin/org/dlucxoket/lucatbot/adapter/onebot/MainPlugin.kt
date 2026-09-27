package org.dlucxoket.lucatbot.adapter.onebot

import com.mikuac.shiro.core.Bot
import com.mikuac.shiro.core.BotPlugin
import com.mikuac.shiro.dto.event.message.AnyMessageEvent
import com.mikuac.shiro.dto.event.request.FriendAddRequestEvent
import com.mikuac.shiro.enums.MsgTypeEnum
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.AgentChain
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
 * 职责：协议适配 + 流程编排
 * - 消息转换（Shiro → model）
 * - 状态机决策（LucatBot）
 * - 调度执行（AgentChain）
 * - 回复发送
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
            if (plainText.isBlank()) {
                return MESSAGE_IGNORE
            }

            // 判断是否 @我 或 @全体（@全体判断在 model 层）
            val isAtMe = isAtMe(event, bot.selfId)
            val isAtAll = (modelEvent.message as? ChatMessage)?.isAtAll() ?: false

            // LucatBot 状态机决策
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

            // 记录到会话历史
            val convId = modelEvent.conversationId
            sessionManager.addMessage(convId, modelEvent.sender.nickname, plainText)
            val history = sessionManager.getContextSummary(convId)

            // 打字延迟（模拟真人不秒回）
            if (TimeAwareness.shouldDelay()) {
                val delay = TimeAwareness.typingDelay(plainText.length)
                log.info("模拟打字延迟 ${delay}ms")
                Thread.sleep(delay)
            }

            // 构建带历史+记忆的上下文
            val fatigueHint = sessionManager.getFatigueHint(convId)
            val memorySummary = memoryService.getMemorySummary(modelEvent.sender.qqNumber)

            val contextWithHistory = """
                当前消息：$plainText

                聊天记录：
                $history

                $memorySummary
                $fatigueHint
            """.trimIndent()

            val reply = agentChain.execute(contextWithHistory)

            if (reply.isNotBlank()) {
                sessionManager.addMessage(convId, lucatBot.persona.name, reply, isBot = true)

                // 自动提取并记忆（模拟真人记住聊天内容）
                extractAndRemember(modelEvent.sender.qqNumber, plainText, reply)

                // 模拟真人拆多条发送
                val messages = splitReply(reply)
                for ((i, msg) in messages.withIndex()) {
                    bot.sendMsg(event, msg, false)
                    // 多条之间加间隔（模拟打字）
                    if (i < messages.size - 1) {
                        Thread.sleep((300..800).random().toLong())
                    }
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

    /**
     * 自动提取并记忆聊天内容
     *
     * 模拟真人自然地记住对话中的关键信息。
     */
    private fun extractAndRemember(userId: Long, userMessage: String, botReply: String) {
        // 记住用户说了什么（事件记忆）
        memoryService.rememberEvent(userId, "对我说了：$userMessage")

        // 简单规则提取事实（"我叫XX"、"我是XX"、"我喜欢XX"）
        val nameMatch = Regex("我叫(\\S+)").find(userMessage)
        if (nameMatch != null) {
            memoryService.rememberFact(userId, "他叫${nameMatch.groupValues[1]}", tags = listOf("名字"))
        }

        val likeMatch = Regex("我(喜欢|爱)(\\S+)").find(userMessage)
        if (likeMatch != null) {
            memoryService.rememberFact(userId, "他喜欢${likeMatch.groupValues[2]}", tags = listOf("喜好"))
        }

        // 定期持久化
        if (Math.random() < 0.3) {
            memoryService.flush()
        }
    }

    /**
     * 拆分回复为多条短消息
     *
     * 模拟真人不会发一大段，而是拆几条：
     * - 句号/换行处断开
     * - 每条不超过 30 字
     * - 最多拆 3 条
     */
    private fun splitReply(reply: String): List<String> {
        // 短回复不拆
        if (reply.length <= 15) return listOf(reply)

        // 按句号/换行拆分
        val sentences = reply.split(Regex("(?<=[。！？!?\\n])"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // 合并短句为不超过 30 字的段落
        val result = mutableListOf<String>()
        var current = StringBuilder()
        for (sentence in sentences) {
            if (current.length + sentence.length > 30 && current.isNotEmpty()) {
                result.add(current.toString().trim())
                current = StringBuilder()
            }
            current.append(sentence)
        }
        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }

        // 最多拆 3 条，多余的合并到最后一条
        return if (result.size > 3) {
            result.take(2) + result.drop(2).joinToString("")
        } else {
            result
        }
    }
}

package org.dlucxoket.lucatbot.bot.scheduler

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.AgentChain
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 主动消息调度器
 *
 * **模拟真人定期看一眼 QQ 的行为**：
 * - 不是每秒都在看（定时轮询）
 * - 看到有未读 → 决定进哪个会话
 * - 进入会话 → 看内容 → 回复 → 标记已读
 * - 深夜/忙碌时降低频率
 */
@Component
class ProactiveScheduler(
    private val lucatBot: LucatBot,
    private val agentChain: AgentChain
) {
    private val log = LoggerFactory.getLogger(ProactiveScheduler::class.java)

    @Autowired
    private lateinit var sessionManager: SessionManager

    @Autowired
    private lateinit var memoryService: MemoryService

    @Scheduled(fixedDelay = 30000)  // 30秒
    fun checkUnread() {
        try {
            if (TimeAwareness.isLateNight()) {
                log.debug("深夜模式，跳过主动检查")
                return
            }

            lucatBot.mood = BotMood.guessByTime()

            val summary = lucatBot.getUnreadSummary()
            if (summary.isEmpty()) return

            log.info("发现未读会话: $summary（当前状态：${lucatBot.state}）")

            // 构建未读概况（含人物信息）
            val summaryText = summary.entries.joinToString("\n") { (convId, count) ->
                val atMe = if (lucatBot.hasUnreadAtMe(convId)) "（@我）" else ""
                val atAll = if (lucatBot.hasUnreadAtAll(convId)) "（@全体）" else ""
                val history = sessionManager.getContextSummary(convId)
                val people = getPeopleInfo(convId)
                "- $convId: ${count}条未读$atMe$atAll\n  最近消息：$history$people"
            }

            val timeHint = when {
                TimeAwareness.isLateNight() -> "深夜"
                TimeAwareness.isWorkTime() -> "工作时间"
                TimeAwareness.isWeekend() -> "周末"
                else -> "普通时间"
            }

            // 进入会话列表状态
            lucatBot.backToConversationList()
            log.info("状态切换 → ViewingConversations")

            val context = """
                未读消息概况：
                $summaryText

                你是「${lucatBot.persona.name}」。
                ${lucatBot.persona.toPrompt()}
                ${lucatBot.mood.toPrompt()}
                当前时间：$timeHint

                请决定接下来做什么：
                1. 如果想看某个会话，进入它并回复
                2. 如果不想看，什么都不做
            """.trimIndent()

            val reply = agentChain.execute(context)

            // 判断是否真的要回复（排除"不想理"的内心独白）
            val shouldReply = reply.isNotBlank() && !isInnerThought(reply)

            if (shouldReply) {
                val selectedConv = summary.keys.firstOrNull()
                if (selectedConv != null) {
                    val parts = selectedConv.split(":")
                    val type = if (parts[0] == "group") LucatBot.ConversationType.GROUP else LucatBot.ConversationType.PRIVATE
                    lucatBot.enterConversation(selectedConv, type)
                    log.info("进入会话: $selectedConv")

                    sendMessage(selectedConv, reply)
                    lucatBot.consumeUnread(selectedConv)
                }
            } else {
                log.info("决定不回复（内心独白）：{}", reply.take(50))
            }

            // 处理完回到空闲状态
            lucatBot.goIdle()
            log.info("状态切换 → Idle")

        } catch (e: Exception) {
            log.error("主动检查异常: ${e.message}")
            lucatBot.goIdle()  // 异常时也恢复状态
        }
    }

    /**
     * 判断是否是内心独白（不是要发出去的消息）
     *
     * 真人看到消息可能只是心里想想"懒得回"，不会真的发出去。
     */
    private fun isInnerThought(reply: String): Boolean {
        val innerMarkers = listOf(
            "先不点", "懒得", "不想看", "不想回", "算了", "不理",
            "没什么好", "不感兴趣", "无所谓", "不重要", "跳过"
        )
        return innerMarkers.any { reply.contains(it) }
    }

    /**
     * 发送消息到指定会话
     */
    private fun sendMessage(conversationId: String, content: String) {
        val bot = lucatBot.shiroBot
        if (bot == null) {
            log.warn("Bot 未绑定，无法发送消息")
            return
        }

        try {
            val parts = conversationId.split(":")
            if (parts.size != 2) return

            val id = parts[1].toLongOrNull() ?: return
            val type = parts[0]

            when (type) {
                "group" -> bot.sendGroupMsg(id, content, false)
                "private" -> bot.sendPrivateMsg(id, content, false)
            }
            log.info("发送消息到 $conversationId: {}", content.take(50))
        } catch (e: Exception) {
            log.error("发送消息失败: {}", e.message)
        }
    }

    /**
     * 获取会话中出现的人的基本信息和记忆
     *
     * 避免频繁调用 LLM 查询用户信息。
     */
    private fun getPeopleInfo(conversationId: String): String {
        val history = sessionManager.getHistory(conversationId, limit = 5)
        val senderIds = history.filter { !it.isBot }.map { it.senderId }.distinct().take(3)

        if (senderIds.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n  相关人物：")
        senderIds.forEach { id ->
            val memory = memoryService.getMemorySummary(id)
            if (memory.isNotBlank()) {
                sb.append("\n  - $id 的记忆：$memory")
            }
        }
        return sb.toString()
    }
}

package org.dlucxoket.lucatbot.bot.scheduler

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.AgentChain
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 主动消息调度器
 *
 * **模拟真人定期看一眼 QQ 的行为**：
 * - 不是每秒都在看（定时轮询）
 * - 看到有未读 → 决定进哪个会话（SessionPickerAgent）
 * - 进入会话 → 看内容 → 决定怎么回
 * - 深夜/忙碌时降低频率
 *
 * 核心流程：
 * 1. 检查是否有未读
 * 2. 有 → SessionPickerAgent 决定进哪个
 * 3. 进入会话 → 获取内容 → ReplyAgent 生成回复
 * 4. 发送回复
 */
@Component
class ProactiveScheduler(
    private val lucatBot: LucatBot,
    private val agentChain: AgentChain
) {
    private val log = LoggerFactory.getLogger(ProactiveScheduler::class.java)

    @Scheduled(fixedDelay = 30000)  // 30秒
    fun checkUnread() {
        try {
            if (TimeAwareness.isLateNight()) {
                log.debug("深夜模式，跳过主动检查")
                return
            }

            // 更新心情
            lucatBot.mood = BotMood.guessByTime()

            val summary = lucatBot.getUnreadSummary()
            if (summary.isEmpty()) return

            log.info("发现未读会话: $summary")

            val summaryText = summary.entries.joinToString("\n") { (convId, count) ->
                val atMe = if (lucatBot.hasUnreadAtMe(convId)) "（@我）" else ""
                val atAll = if (lucatBot.hasUnreadAtAll(convId)) "（@全体）" else ""
                "- $convId: ${count}条未读$atMe$atAll"
            }

            val timeHint = when {
                TimeAwareness.isLateNight() -> "深夜"
                TimeAwareness.isWorkTime() -> "工作时间"
                TimeAwareness.isWeekend() -> "周末"
                else -> "普通时间"
            }

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

            agentChain.execute(context)

        } catch (e: Exception) {
            log.error("主动检查异常: ${e.message}")
        }
    }
}

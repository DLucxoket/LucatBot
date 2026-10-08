package org.dlucxoket.lucatbot.bot.scheduler

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.loop.MainLoopController
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 空闲定时唤醒
 *
 * 主循环自己会按心情休眠 3~20min；这里做兜底巡查：
 * 空闲期间攒了**值得叫醒的未读**（私聊 / @我 / @全体 / 回复我）时缩短休眠提前醒来。
 *
 * **普通群聊消息不唤醒**（应主人要求）：闲聊未读只攒着，等主循环按心情自然醒来；
 * 旧规则「未读 ≥3 条就唤醒」已删——那会让群聊一热闹就把空闲的机器人拽起来。
 */
@Component
class ProactiveScheduler(
    private val lucatBot: LucatBot,
    private val mainLoopController: MainLoopController
) {
    private val log = LoggerFactory.getLogger(ProactiveScheduler::class.java)

    @Scheduled(fixedDelay = 30000)  // 30秒
    fun checkUnread() {
        try {
            if (TimeAwareness.isLateNight()) {
                log.debug("深夜模式，跳过主动检查")
                return
            }

            val summary = lucatBot.getUnreadSummary()
            if (summary.isEmpty()) return

            // 只有「冲我来的」未读才配把空闲的机器人叫醒（普通群聊消息不算）
            val wakeWorthy = summary.keys.any { lucatBot.hasWakeWorthyUnread(it) }

            if (mainLoopController.isIdleSleeping && wakeWorthy) {
                log.info("空闲中发现值得处理的未读 {}，唤醒主循环", summary)
                mainLoopController.notifyUrgent()
            }
        } catch (e: Exception) {
            log.error("主动检查异常: ${e.message}")
        }
    }
}

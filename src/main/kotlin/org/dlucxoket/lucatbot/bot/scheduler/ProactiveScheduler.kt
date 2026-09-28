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
 * 空闲期间攒了未读（尤其 @我/@全体）时，缩短休眠提前醒来。
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

            val urgent = summary.keys.any {
                lucatBot.hasUnreadAtMe(it) || lucatBot.hasUnreadAtAll(it)
            }
            val total = summary.values.sum()

            // 空闲休眠中且有值得看的未读 → 唤醒
            if (mainLoopController.isIdleSleeping && (urgent || total >= 3)) {
                log.info("空闲中发现未读会话 {}，唤醒主循环", summary)
                mainLoopController.notifyUrgent()
            }
        } catch (e: Exception) {
            log.error("主动检查异常: ${e.message}")
        }
    }
}

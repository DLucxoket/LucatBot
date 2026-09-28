package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 主循环编排（状态机驱动）
 *
 * 状态流转：消息列表 → 选会话/空闲；会话中 → 返回消息列表。
 * 固定脚本调专用 Agent + Tool（SessionPickerAgent / ConversationAgent），无自由调度链路。
 */
@Component
class MainLoopController(
    private val lucatBot: LucatBot,
    private val messageListController: MessageListController,
    private val conversationController: ConversationController,
    private val actionLog: ActionLog
) {
    private val log = LoggerFactory.getLogger(MainLoopController::class.java)

    private val lock = Object()
    @Volatile private var wakeRequested = false
    @Volatile private var running = false
    private var loopThread: Thread? = null
    @Volatile private var lastIdleStartMs = 0L

    /** 是否处于空闲休眠中 */
    val isIdleSleeping: Boolean
        get() = lucatBot.state is LucatBot.State.Idle && running

    /**
     * 启动主循环（幂等；由 MainPlugin 首次绑定 Bot 时调用）
     */
    @Synchronized
    fun startIfNeeded() {
        if (running) return
        running = true
        lucatBot.backToConversationList()
        loopThread = Thread({ runLoop() }, "lucatbot-main-loop").apply {
            isDaemon = true
            start()
        }
        log.info("主循环已启动")
    }

    /**
     * 空闲唤醒信号：@我/@全体 缩短休眠（最少再等 5s）
     */
    fun notifyUrgent() {
        synchronized(lock) {
            wakeRequested = true
            lock.notifyAll()
        }
    }

    fun stop() {
        running = false
        notifyUrgent()
    }

    private fun runLoop() {
        var fromIdle = false
        while (running) {
            try {
                when (val state = lucatBot.state) {
                    is LucatBot.State.ViewingConversations -> {
                        val pick = messageListController.pickSession(fromIdleWake = fromIdle)
                        if (pick == null) {
                            enterIdleAndSleep()
                            fromIdle = true
                        } else {
                            fromIdle = false
                            if (pick.conversationType ==
                                org.dlucxoket.lucatbot.bot.agent.data.SessionPickType.PROACTIVE
                            ) {
                                actionLog.append("主动找人 ${pick.targetId}")
                            }
                            conversationController.run(pick)
                            // 会话结束后回到消息列表，小憩一下模拟人
                            Thread.sleep(1_000L)
                        }
                    }

                    is LucatBot.State.Idle -> {
                        // 从空闲状态恢复（异常路径）
                        lucatBot.backToConversationList()
                    }

                    is LucatBot.State.InConversation -> {
                        // 会话循环由 ConversationController 串行驱动；顶层级见到此状态只做恢复
                        lucatBot.backToConversationList()
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                log.error("主循环异常: {}", e.message, e)
                try {
                    Thread.sleep(5_000L)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        log.info("主循环已停止")
    }

    /**
     * 进入空闲并休眠 10~60s（按心情加权，测试用短值）；@我/@全体 可缩短（最少再等 5s）
     */
    private fun enterIdleAndSleep() {
        lucatBot.goIdle()
        val sleepMs = idleMillis(lucatBot.mood)
        lastIdleStartMs = System.currentTimeMillis()
        actionLog.append("进入空闲（约 ${sleepMs / 60000} 分钟）")
        log.info("进入空闲，休眠 {} ms", sleepMs)

        val deadline = System.currentTimeMillis() + sleepMs
        synchronized(lock) {
            wakeRequested = false
            while (running && !wakeRequested && System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                lock.wait(remaining)
            }
            if (wakeRequested && running) {
                // 最少再等 5s，模拟真人不会秒醒（测试用短值，正式可恢复 30s）
                lock.wait(5_000L)
            }
        }

        // 空闲结束：decay 或按时间重估
        lucatBot.mood = if (Math.random() < 0.5) lucatBot.mood.decay() else BotMood.guessByTime()
        wakeRequested = false
    }

    companion object {
        // 测试用短休眠（正式可恢复 3min / 20min）
        const val MIN_IDLE_MS = 10 * 1000L
        const val MAX_IDLE_MS = 60 * 1000L

        /**
         * 心情加权的空闲时长：累/困/烦 → 更久，开心 → 更短。
         */
        fun idleMillis(mood: BotMood, random: java.util.Random = java.util.Random()): Long {
            val factor = when (mood.type) {
                BotMood.MoodType.HAPPY -> 0.15
                BotMood.MoodType.NORMAL -> 0.4
                BotMood.MoodType.BORED -> 0.55
                BotMood.MoodType.ANNOYED -> 0.7
                BotMood.MoodType.TIRED -> 0.85
                BotMood.MoodType.SLEEPY -> 1.0
            }
            // intensity 越高，情绪越极端，向该情绪的时长方向再推一点
            val weighted = (factor * (0.7 + 0.3 * mood.intensity)).coerceIn(0.0, 1.0)
            val span = MAX_IDLE_MS - MIN_IDLE_MS
            return MIN_IDLE_MS + (span * weighted * (0.8 + 0.4 * random.nextDouble())).toLong()
                .coerceAtMost(span)
        }
    }
}

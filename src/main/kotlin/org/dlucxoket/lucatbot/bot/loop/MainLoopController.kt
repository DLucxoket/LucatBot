package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 主循环编排（状态机驱动）
 *
 * 状态流转：消息列表 → 选会话/空闲；会话中 → 返回消息列表。
 * 固定脚本调专用 Agent + Tool（SessionPickerAgent / ConversationAgent），无自由调度链路。
 *
 * **启动时机**：Spring 容器就绪即启动（[startOnReady]），不再等首条消息——
 * 原设计由 MainPlugin.onAnyMessage 首次触发，WS 连接后若一直无消息主循环
 * 永不启动，ProactiveScheduler（依赖 isIdleSleeping）也叫不醒它，主动找人
 * 在安静时段完全失效（实盘：connected 与主循环启动隔 86 秒）。
 * 冷启动通常无未读 → 首轮 pick 后直接 idle；shiroBot 未绑定时发送有
 * 「Bot 未绑定」护栏。`mainloop.auto-start=false` 可关闭（Spring 冒烟测试用，
 * 避免测试上下文里真起循环打 LLM）。
 */
@Component
class MainLoopController(
    private val lucatBot: LucatBot,
    private val messageListController: MessageListController,
    private val conversationController: ConversationController,
    private val actionLog: ActionLog
) {
    private val log = LoggerFactory.getLogger(MainLoopController::class.java)

    /** 就绪自启开关（yaml `mainloop.auto-start`，默认 true；测试上下文关闭） */
    @Value("\${mainloop.auto-start:true}")
    private var autoStart: Boolean = true

    private val lock = Object()
    @Volatile private var wakeRequested = false
    @Volatile private var running = false
    private var loopThread: Thread? = null
    @Volatile private var lastIdleStartMs = 0L

    /** 是否处于空闲休眠中 */
    val isIdleSleeping: Boolean
        get() = lucatBot.state is LucatBot.State.Idle && running

    /**
     * Spring 容器就绪即启动主循环（幂等；[MainPlugin] 首条消息处的调用保留作双保险）
     */
    @EventListener(ApplicationReadyEvent::class)
    fun startOnReady() {
        if (!autoStart) {
            log.debug("mainloop.auto-start=false，跳过就绪自启")
            return
        }
        log.info("Spring 就绪，启动主循环（不等首条消息）")
        startIfNeeded()
    }

    /**
     * 启动主循环（幂等；由 MainPlugin 首条消息处调用作双保险）
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

    /**
     * 会话执行互斥锁——主循环的「选会话+跑会话」与通知通道的「当场跳转」串行
     *
     * 同一时刻只允许一个会话循环（两套 loop 并发会抢 drain 待插入、互改 state）。
     * 空闲休眠与会话间歇在**锁外**，通知跳转才有机可乘；主循环跑会话期间
     * 通知的 tryLock 失败 → 降级入未读（下轮 pick 高优先级处理）。
     */
    private val sessionGate = java.util.concurrent.locks.ReentrantLock()

    /** 主循环侧：持锁执行选会话+会话段 */
    private fun <T> withSessionGate(block: () -> T): T {
        sessionGate.lock()
        try {
            return block()
        } finally {
            sessionGate.unlock()
        }
    }

    /**
     * 通知通道侧：**当场跳转**执行一段会话
     *
     * tryLock 失败（主循环正跑另一会话/选会话）→ **请求当前会话让位**
     * （[LucatBot.requestSessionYield]：置位 + 唤醒 STAY 等待——会话循环在
     * 下一轮检查点记 todo 后退出释放锁），再等最多 [yieldWaitMs]；
     * 仍拿不到（如主循环在选会话的 LLM 里、或被禁言会话让位中）→ 返回 false，
     * 调用方降级入未读（下轮 pick 高优先级）。
     *
     * @return false = 没拿到会话执行权——调用方降级
     */
    // 注意：默认参数必须在 block 之前——Kotlin 尾随 lambda 绑定最后一个形参
    fun tryRunSessionExclusive(
        yieldWaitMs: Long = 15_000L,
        block: () -> Unit
    ): Boolean {
        if (!sessionGate.tryLock()) {
            lucatBot.requestSessionYield()
            val acquired = try {
                sessionGate.tryLock(yieldWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!acquired) return false
            try {
                block()
            } finally {
                sessionGate.unlock()
            }
            return true
        }
        try {
            block()
        } finally {
            sessionGate.unlock()
        }
        return true
    }

    private fun runLoop() {
        var fromIdle = false
        while (running) {
            try {
                when (val state = lucatBot.state) {
                    is LucatBot.State.ViewingConversations -> {
                        // pick + 会话段持锁（与通知跳转互斥）；idle 休眠与会话间歇在锁外
                        val pick = withSessionGate {
                            val p = messageListController.pickSession(fromIdleWake = fromIdle)
                            if (p != null) {
                                if (p.conversationType ==
                                    org.dlucxoket.lucatbot.bot.agent.data.SessionPickType.PROACTIVE
                                ) {
                                    actionLog.append("主动找人 ${p.targetId}")
                                }
                                conversationController.run(p)
                            }
                            p
                        }
                        if (pick == null) {
                            enterIdleAndSleep()
                            fromIdle = true
                        } else {
                            fromIdle = false
                            // 会话结束后回到消息列表，小憩一下模拟人（锁外，通知可插入）
                            Thread.sleep(1_000L)
                        }
                    }

                    is LucatBot.State.Idle -> {
                        // 从空闲状态恢复（异常路径）——锁内，避免与通知跳转互改 state
                        withSessionGate { lucatBot.backToConversationList() }
                    }

                    is LucatBot.State.InConversation -> {
                        // 会话循环由会话执行锁串行驱动：读到此状态说明通知会话正跑
                        // 或刚结束——持锁等它完成后再恢复，不并发踢掉别人的会话
                        withSessionGate { lucatBot.backToConversationList() }
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
     * 进入空闲并休眠——**时长由 LLM 决定**（idle 的 `params.seconds`，钳 600~7200s
     * 即 10 分钟 ~ 2 小时），LLM 没给才回退到按心情随机（[idleMillis]，同范围）；
     * @我/@全体 等 urgent 仍可提前唤醒（ProactiveScheduler 30s 巡查兜底）
     */
    private fun enterIdleAndSleep() {
        lucatBot.goIdle()
        val sleepMs = resolveIdleMillis(messageListController.consumeIdleSeconds(), lucatBot.mood)
        lastIdleStartMs = System.currentTimeMillis()
        val idleLabel = when {
            sleepMs < 60_000L -> "${sleepMs / 1000} 秒"
            sleepMs < 3_600_000L -> "约 ${sleepMs / 60_000} 分钟"
            else -> "约 ${sleepMs / 3_600_000} 小时"
        }
        actionLog.append("进入空闲（$idleLabel）")
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

        // 空闲结束：decay 或按时间重估（updateMood 原子读-改-写，防与消息线程并发丢更新）
        lucatBot.updateMood { if (Math.random() < 0.5) it.decay() else BotMood.guessByTime() }
        wakeRequested = false
    }

    companion object {
        /** 心情随机兜底的空闲范围（LLM 没给 seconds 时用）：10 分钟 ~ 2 小时，与 LLM 钳制同档 */
        const val MIN_IDLE_MS = 10 * 60 * 1000L
        const val MAX_IDLE_MS = 2 * 60 * 60 * 1000L

        /**
         * LLM 指定空闲秒数的钳制范围（**10 分钟 ~ 2 小时**）
         *
         * 2026-10-01 应主人要求整体调长：此前 5s~30min 太短——真人看完列表
         * 再打开 QQ 本就是十几分钟起，几秒级的空闲像挂机。下限 600s 会把
         * 模型偶尔给的短值一并抬起来（想快看消息的场景由 urgent 唤醒兜底，
         * 不靠缩短空闲）。
         */
        const val MIN_LLM_IDLE_SECONDS = 600
        const val MAX_LLM_IDLE_SECONDS = 7200

        /**
         * 空闲休眠时长：**LLM 在 idle 里给的 seconds 优先**（钳 600~7200s——
         * 10 分钟 ~ 2 小时，与「真人不上线」的体感一致）；没给（0：LLM 省略
         * 或解析兜底）才回退到按心情随机 [idleMillis]（同范围）
         */
        fun resolveIdleMillis(
            llmSeconds: Int,
            mood: BotMood,
            random: java.util.Random = java.util.Random()
        ): Long =
            if (llmSeconds > 0) {
                llmSeconds.coerceIn(MIN_LLM_IDLE_SECONDS, MAX_LLM_IDLE_SECONDS).toLong() * 1000L
            } else {
                idleMillis(mood, random)
            }

        /**
         * 心情加权的空闲时长：累/困/烦 → 更久（贴近 2h 端），开心 → 更短（贴近 10min 端）。
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

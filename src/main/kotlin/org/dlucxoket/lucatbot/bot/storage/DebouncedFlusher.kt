package org.dlucxoket.lucatbot.bot.storage

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 防抖批量落盘器
 *
 * save 后不立刻写盘，等 [delayMs] 内没有新写入再统一 flush；
 * 进程退出时由 [shutdown] 兜底写盘。
 *
 * 两个并发要点：
 * - **最大等待（防饥饿）**：持续写入会不断重置防抖计时，若只靠 delay，
 *   高频写入期间 flush 会被无限推迟——首次 markDirty 起超过 [maxWaitMs]
 *   后，下一次 markDirty 直接按 0 延迟调度，保证落盘有界延迟。
 * - **flush 互斥**：调度线程与 shutdown 线程都可能执行 [flushAction]，
 *   统一在 [flushLock] 下串行，避免两个线程同时写同一个文件互相交错。
 */
class DebouncedFlusher(
    private val flushAction: () -> Unit,
    private val delayMs: Long = 5_000L,
    private val maxWaitMs: Long = 30_000L,
    private val name: String = "debounced-flush"
) {
    private val log = LoggerFactory.getLogger(DebouncedFlusher::class.java)
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, name).apply { isDaemon = true }
        }

    /** 状态锁：只保护 pending / firstMarkAt，不与 flushAction 嵌套 */
    private val stateLock = Object()

    /** flush 互斥锁：调度线程与 shutdown 线程串行执行 flushAction */
    private val flushLock = Any()

    private var pending: ScheduledFuture<*>? = null

    /** 本轮首次 markDirty 的时间（0 = 当前无待落盘周期）；防饥饿用 */
    private var firstMarkAt = 0L

    /** 是否已停机（shutdown 之后调度器拒绝新任务） */
    @Volatile
    private var stopped = false

    /**
     * 标记一次写入，重置防抖计时；距首次标记超过 [maxWaitMs] 时立即落盘。
     *
     * 停机之后（[shutdown] 已调用、调度器已关）再收到写入时**同步落盘**：
     * 这是「停机迟到写入」路径——主循环线程可能在仓库 shutdown 之后还在
     * 写记忆，直接抛 RejectedExecutionException 会把这条数据丢掉。
     */
    fun markDirty() {
        var inlineFlush = false
        synchronized(stateLock) {
            if (stopped) {
                inlineFlush = true
            } else {
                val now = System.currentTimeMillis()
                if (firstMarkAt == 0L) firstMarkAt = now
                val waited = now - firstMarkAt
                val delay = if (waited >= maxWaitMs) 0L else delayMs.coerceAtMost(maxWaitMs - waited)

                pending?.cancel(false)
                inlineFlush = try {
                    pending = scheduler.schedule({
                        // 先开新周期再 flush：flush 期间的新写入会重新 firstMarkAt，不丢下一轮
                        synchronized(stateLock) { firstMarkAt = 0L }
                        runFlush()
                    }, delay, TimeUnit.MILLISECONDS)
                    false
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // 竞态：调度器在检查后瞬间被关——同样转同步落盘，不丢数据
                    true
                }
            }
        }
        if (inlineFlush) runFlush()
    }

    /** 兜底：取消计时并立即落盘、关闭调度器 */
    fun shutdown() {
        synchronized(stateLock) {
            stopped = true
            pending?.cancel(false)
            pending = null
            firstMarkAt = 0L
        }
        runFlush()
        scheduler.shutdown()
    }

    /** 串行执行 flushAction（调度线程与 shutdown 线程互斥） */
    private fun runFlush() {
        synchronized(flushLock) {
            try {
                flushAction()
            } catch (e: Exception) {
                log.error("[{}] 防抖落盘失败: {}", name, e.message)
            }
        }
    }
}

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
 * 进程退出时由 [shutdown] 兜底写盘。单线程调度，天然串行。
 */
class DebouncedFlusher(
    private val flushAction: () -> Unit,
    private val delayMs: Long = 5_000L,
    private val name: String = "debounced-flush"
) {
    private val log = LoggerFactory.getLogger(DebouncedFlusher::class.java)
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, name).apply { isDaemon = true }
        }

    @Volatile
    private var pending: ScheduledFuture<*>? = null

    /** 标记一次写入，重置防抖计时 */
    @Synchronized
    fun markDirty() {
        pending?.cancel(false)
        pending = scheduler.schedule({
            try {
                flushAction()
            } catch (e: Exception) {
                log.error("[{}] 防抖落盘失败: {}", name, e.message)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** 兜底：取消计时并立即落盘、关闭调度器 */
    @Synchronized
    fun shutdown() {
        pending?.cancel(false)
        pending = null
        try {
            flushAction()
        } catch (e: Exception) {
            log.error("[{}] 退出落盘失败: {}", name, e.message)
        }
        scheduler.shutdown()
    }
}

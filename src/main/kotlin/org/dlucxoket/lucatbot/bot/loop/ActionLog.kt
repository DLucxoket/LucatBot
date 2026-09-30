package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.storage.DebouncedFlusher
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 操作日志条目（结构化——落库即四列：时间 / 所处会话 / 操作 / 原因）
 *
 * @param timestamp 记录时间（epoch ms）
 * @param conversationId 发生时所处会话（null = 消息列表/空闲等无会话态）
 * @param action 操作本身（客观描述）
 * @param reason 决策动机（可空；注入 prompt 时拼在操作后「（…）」）
 */
data class ActionLogEntry(
    val timestamp: Long,
    val conversationId: String? = null,
    val action: String,
    val reason: String? = null
)

/**
 * 滚动操作日志（**全局、跨会话、窗口滚动、结构化持久化**）
 *
 * 让机器人知道自己刚才干了什么——消息列表 / 会话内 / 通知通道的 prompt
 * 都注入同一份（[LoopConfig] 单例 Bean），进入会话不清空：
 * - **结构化记录**：每条 = 时间 + 所处会话 + 操作 + 原因（[ActionLogEntry]），
 *   持久化为 mysql `action_log` 表（第七张）/ `data/action-log.json`
 * - **prompt 渲染**：`[HH:mm][会话] 操作（原因）`——会话标签让跨会话的日志
 *   分得清「哪条是在哪个会话干的」；原因 ≤30 字（调用方截断 + 此处兜底）
 * - **窗口滚动**：只保留最近 [maxLines] 行，超出丢最旧
 * - **持久化**：[repository] 非空时启动恢复、写入防抖落盘（进程退出兜底）；
 *   测试默认 null（纯内存，不污染 data/）
 */
class ActionLog(
    private val maxLines: Int = 20,
    /** 持久化仓库（null = 纯内存）；生产由 LoopConfig 注入 storage.type 对应实现 */
    private val repository: ActionLogRepository? = null
) {
    private val log = LoggerFactory.getLogger(ActionLog::class.java)
    private val entries = CopyOnWriteArrayList<ActionLogEntry>()
    /** 自上次成功 flush 以来新增的条目（**只送增量**——历史不重写、不删） */
    private val pendingAdds = CopyOnWriteArrayList<ActionLogEntry>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    /** 防抖落盘（仅持久化模式）：append 后约 5s 批量写，shutdown 兜底 */
    private val flusher = repository?.let {
        DebouncedFlusher(
            flushAction = { doFlush() },
            delayMs = 5_000L,
            name = "action-log-flush"
        )
    }

    init {
        repository?.let { repo ->
            runCatching { entries.addAll(repo.loadEntries().takeLast(maxLines)) }
                .onFailure { e -> log.warn("操作日志恢复失败（以空启动）: {}", e.message) }
        }
    }

    /**
     * 追加一条（时间取当前；自动滚动窗口）
     *
     * @param action 操作本身（客观描述）
     * @param reason 决策动机（可空；截 30 字兜底）
     * @param conversationId 发生时所处会话（null = 消息列表/空闲态）
     */
    fun append(action: String, reason: String? = null, conversationId: String? = null) {
        val entry = ActionLogEntry(
            timestamp = System.currentTimeMillis(),
            conversationId = conversationId?.takeIf { it.isNotBlank() },
            action = action,
            reason = reason?.trim()?.take(30)?.takeIf { it.isNotEmpty() }
        )
        entries.add(entry)
        pendingAdds.add(entry) // 排队等增量 flush（历史不重写）
        while (entries.size > maxLines) {
            entries.removeAt(0)
        }
        flusher?.markDirty()
    }

    /** 导出为多行文本（注入 prompt）；空日志返回占位说明 */
    fun dump(): String {
        if (entries.isEmpty()) return "（暂无）"
        return entries.joinToString("\n") { e ->
            buildString {
                append("[")
                append(LocalTime.ofInstant(Instant.ofEpochMilli(e.timestamp), zone).format(timeFormat))
                append("]")
                e.conversationId?.let { append("[$it]") }
                append(" ")
                append(e.action)
                e.reason?.let { append("（$it）") }
            }
        }
    }

    fun size(): Int = entries.size

    fun clear() {
        entries.clear()
        pendingAdds.clear()
        flusher?.markDirty()
    }

    /** 停机兜底：立即写盘并关防抖调度（LoopConfig destroyMethod 调用；纯内存模式 no-op） */
    fun shutdown() {
        flusher?.flushNow()
        flusher?.shutdown()
    }

    /**
     * 防抖落盘的实际动作：**只送自上次成功 flush 之后的新增条目**
     *
     * 历史不在这里重写（持久层不删行、也不重复插入）；本批写失败则保留在
     * pending 里等下一次 flush，不丢。
     */
    private fun doFlush() {
        val repo = repository ?: return
        val batch = pendingAdds.toList()
        if (batch.isEmpty()) return
        runCatching { repo.appendEntries(batch) }
            .onSuccess { pendingAdds.removeAll(batch.toSet()) }
            .onFailure { e -> log.error("操作日志持久化失败: {}", e.message) }
    }
}

package org.dlucxoket.lucatbot.bot.loop

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.storage.DebouncedFlusher
import org.dlucxoket.lucatbot.bot.storage.JsonFiles
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 滚动操作日志（**全局、跨会话、窗口滚动、可持久化**）
 *
 * 让机器人知道自己刚才干了什么——消息列表 / 会话内 / 通知通道的 prompt
 * 都注入同一份（[LoopConfig] 单例 Bean），进入会话不清空：
 * - **记操作 + 简短动机**：动作行尾挂本轮决策的 reason（≤30 字，
 *   「回复：哈哈（接住话茬）」——操作本身与内心动机一条讲完，不额外加行省 token）
 * - **窗口滚动**：只保留最近 [maxLines] 行，超出丢最旧
 * - **持久化**：[filePath] 非空时启动恢复、写入防抖落盘（进程退出兜底）——
 *   重启后仍记得自己干过什么；测试默认 null（纯内存，不污染 data/）
 */
class ActionLog(
    private val maxLines: Int = 20,
    /** 持久化文件路径（null = 纯内存）；生产由 LoopConfig 显式传 `data/action-log.json` */
    private val filePath: String? = null
) {
    private val log = LoggerFactory.getLogger(ActionLog::class.java)
    private val mapper = jacksonObjectMapper()
    private val lines = CopyOnWriteArrayList<String>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    /** 防抖落盘（仅持久化模式）：append 后约 5s 批量写，shutdown 兜底 */
    private val flusher = filePath?.let {
        DebouncedFlusher(
            flushAction = { doFlush() },
            delayMs = 5_000L,
            name = "action-log-flush"
        )
    }

    init {
        filePath?.let { load(it) }
    }

    /** 追加一行（自动带时间戳）；滚动窗口 + 触发防抖落盘 */
    fun append(action: String) {
        val line = "[${LocalTime.now().format(timeFormat)}] $action"
        lines.add(line)
        while (lines.size > maxLines) {
            lines.removeAt(0)
        }
        flusher?.markDirty()
    }

    /** 导出为多行文本（注入 prompt）；空日志返回占位说明 */
    fun dump(): String {
        return if (lines.isEmpty()) "（暂无）" else lines.joinToString("\n")
    }

    fun size(): Int = lines.size

    fun clear() {
        lines.clear()
        flusher?.markDirty()
    }

    /** 停机兜底：立即写盘并关防抖调度（LoopConfig destroyMethod 调用；纯内存模式 no-op） */
    fun shutdown() {
        flusher?.flushNow()
        flusher?.shutdown()
    }

    private fun doFlush() {
        val path = filePath ?: return
        try {
            JsonFiles.writeAtomic(mapper, File(path), lines.toList())
            log.debug("操作日志已持久化（{} 行）", lines.size)
        } catch (e: Exception) {
            log.error("操作日志持久化失败: ${e.message}")
        }
    }

    private fun load(path: String) {
        val file = File(path)
        if (!file.exists()) return
        try {
            val loaded: List<String> = mapper.readValue(file)
            lines.addAll(loaded.takeLast(maxLines))
            log.info("已恢复操作日志 ${lines.size} 行")
        } catch (e: Exception) {
            // 加载失败绝不能让空日志覆盖原文件：改名备份，以空日志启动
            JsonFiles.backupCorrupt(file)
            log.error("操作日志加载失败（${e.message}）：以空启动，原文件已备份")
        }
    }
}

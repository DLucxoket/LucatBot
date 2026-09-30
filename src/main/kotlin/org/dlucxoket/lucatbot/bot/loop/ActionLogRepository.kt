package org.dlucxoket.lucatbot.bot.loop

/**
 * 操作日志仓库（存储抽象）
 *
 * 只需「恢复窗口」与「整窗写回」两种能力——日志只需要**最近 N 条**：
 * - 启动恢复（loadEntries）
 * - 窗口变化后整窗同步（saveEntries；ActionLog 内 5s 防抖调用）
 *
 * 条目是结构化的（时间 / 所处会话 / 操作 / 原因四字段），
 * 落库即 action_log 表的四列，不是拼好的一行文本。
 *
 * DIP：ActionLog 依赖此接口，StorageConfig 按 `storage.type` 绑定实现
 * （mysql → action_log 表 / json → data/action-log.json）。
 */
interface ActionLogRepository {

    /** 启动恢复（按时间升序返回持久化的条目；空/损坏 → 空列表） */
    fun loadEntries(): List<ActionLogEntry>

    /** 整窗写回（ActionLog 的滚动窗口，≤ maxLines 条） */
    fun saveEntries(entries: List<ActionLogEntry>)
}

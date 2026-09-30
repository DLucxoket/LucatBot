package org.dlucxoket.lucatbot.bot.loop

/**
 * 操作日志仓库（存储抽象）
 *
 * 只需「恢复窗口」与「追加新条目」两种能力：
 * - 启动恢复（[loadEntries]）：取最近 N 条进内存滚动窗口
 * - 增量追加（[appendEntries]）：ActionLog 内 5s 防抖，**只送上次 flush 之后
 *   新产生的条目**——历史不重写、更不删
 *
 * **持久化的执行日志不删行**（用户要求）：MySQL 侧只 INSERT，表里留全量历史，
 * 查询时在 SQL 端 `ORDER BY id DESC LIMIT N` 取最近的；json 文件侧自行裁剪
 * 保留窗口（文件不是审计库，不无限膨胀）。
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

    /**
     * 追加**新增**条目（不含已持久化的历史；ActionLog 5s 防抖调用）
     *
     * MySQL：批量 INSERT，**不删任何行**；json：读文件 → 追加 → 裁剪到窗口上限
     */
    fun appendEntries(newEntries: List<ActionLogEntry>)
}

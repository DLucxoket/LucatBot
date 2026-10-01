package org.dlucxoket.lucatbot.bot.storage.mysql

import org.dlucxoket.lucatbot.bot.memory.FixedMemoryChange
import org.dlucxoket.lucatbot.bot.memory.FixedMemoryRepository

/**
 * 固定短记仓库——MySQL 实现（第八张表 `fixed_memory`）
 *
 * **只增不删**：每次更新 INSERT 一行新内容，旧行保留——历史行即「每次更改
 * 都入库」的审计记录；当前内容 = `ORDER BY id DESC LIMIT 1`。
 */
class MySqlFixedMemoryRepository(
    private val storage: MySqlStorage
) : FixedMemoryRepository {

    override fun loadRecent(limit: Int): List<FixedMemoryChange> =
        storage.query(
            "SELECT changed_at, content FROM fixed_memory ORDER BY id DESC LIMIT $limit",
            map = { rs ->
                FixedMemoryChange(
                    content = rs.getString("content"),
                    changedAt = MySqlTime.toEpochMs(rs, "changed_at")
                )
            }
        ).asReversed() // DESC 取最新 → 翻回升序（最后一条 = 当前）

    override fun append(change: FixedMemoryChange) {
        storage.update(
            "INSERT INTO fixed_memory (changed_at, content) VALUES (?,?)",
            MySqlTime.of(change.changedAt),
            change.content
        )
    }

    /** StorageConfig 的 destroyMethod 兜底；短连接无池可关 */
    override fun shutdown() {
        // no-op：写入即落库
    }
}

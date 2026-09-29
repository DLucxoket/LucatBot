package org.dlucxoket.lucatbot.bot.storage.mysql

import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.memory.MemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import java.sql.ResultSet

/**
 * 记忆仓库——MySQL 实现
 *
 * 与 [org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository] 实现同一
 * 接口，StorageConfig 按 `storage.type` 绑定，业务层无感知。
 *
 * 写入即落库（`flush()`/`shutdown()` 为 no-op）。列表字段
 * （tags/relatedUserIds）以 JSON 字符串存 VARCHAR——序列化发生在
 * 存储边界（合规：非 LLM 边界）。
 *
 * [findByTags] 取全量后在内存按交集过滤：语义与 JSON 实现逐字一致，
 * 且记忆量级（百~千行）下没有建全文索引的必要；真正的关键词搜索走
 * MemoryService.search（本就 findAll + 内存过滤）。
 */
class MySqlMemoryRepository(
    private val storage: MySqlStorage
) : MemoryRepository {

    override fun save(memory: MemoryItem) {
        storage.update(INSERT, *argsOf(memory))
    }

    override fun saveAll(memories: List<MemoryItem>) {
        if (memories.isEmpty()) return
        storage.batchUpdate(INSERT, memories.map { argsOf(it) })
    }

    override fun findById(id: String): MemoryItem? =
        storage.query("SELECT * FROM memories WHERE id = ?", id, map = ::mapRow).firstOrNull()

    override fun findByUserId(userId: Long): List<MemoryItem> =
        storage.query(
            "SELECT * FROM memories WHERE user_id = ? ORDER BY created_at",
            userId, map = ::mapRow
        )

    override fun findByTags(tags: List<String>): List<MemoryItem> =
        findAll().filter { item -> item.tags.any { it in tags } }

    override fun delete(id: String) {
        storage.update("DELETE FROM memories WHERE id = ?", id)
    }

    override fun findAll(): List<MemoryItem> =
        storage.query("SELECT * FROM memories ORDER BY created_at", map = ::mapRow)

    override fun flush() {
        // no-op：写入即落库（见 KDoc）
    }

    /** StorageConfig 的 destroyMethod 兜底；短连接无池可关 */
    fun shutdown() {
        // no-op
    }

    private fun argsOf(m: MemoryItem): Array<Any?> = arrayOf(
        m.id, m.userId, m.subjectType.name, m.type.name, m.content,
        storage.mapper.writeValueAsString(m.relatedUserIds),
        storage.mapper.writeValueAsString(m.tags),
        m.strength, m.createdAt
    )

    private fun mapRow(rs: ResultSet): MemoryItem {
        val id = rs.getString("id")
        return MemoryItem(
            id = id,
            userId = rs.getLong("user_id"),
            subjectType = enumOr(rs.getString("subject_type"), MemorySubject.Type.USER),
            type = enumOr(rs.getString("type"), MemoryItem.MemoryType.FACT),
            content = rs.getString("content"),
            relatedUserIds = readLongList(rs.getString("related_user_ids")),
            tags = readStringList(rs.getString("tags")),
            strength = rs.getDouble("strength"),
            createdAt = rs.getLong("created_at")
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, fallback: E): E =
        runCatching { enumValueOf<E>(raw ?: "") }.getOrDefault(fallback)

    private fun readStringList(raw: String?): List<String> =
        runCatching { storage.mapper.readValue(raw.orEmpty(), Array<String>::class.java).toList() }
            .getOrDefault(emptyList())

    private fun readLongList(raw: String?): List<Long> =
        runCatching { storage.mapper.readValue(raw.orEmpty(), Array<Long>::class.java).toList() }
            .getOrDefault(emptyList())

    companion object {
        internal const val INSERT =
            "INSERT INTO memories (id, user_id, subject_type, type, content, related_user_ids, " +
                "tags, strength, created_at) VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), subject_type=VALUES(subject_type), " +
                "type=VALUES(type), content=VALUES(content), related_user_ids=VALUES(related_user_ids), " +
                "tags=VALUES(tags), strength=VALUES(strength), created_at=VALUES(created_at)"
    }
}

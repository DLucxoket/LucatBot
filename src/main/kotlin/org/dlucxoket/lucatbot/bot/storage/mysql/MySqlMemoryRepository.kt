package org.dlucxoket.lucatbot.bot.storage.mysql

import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.memory.MemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import java.sql.Connection
import java.sql.ResultSet

/**
 * 记忆仓库——MySQL 实现（**关键词规范化**）
 *
 * 与 [org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository] 实现同一
 * 接口，StorageConfig 按 `storage.type` 绑定，业务层无感知；
 * 领域模型 [MemoryItem.tags] 保持 `List<String>`，规范化只发生在存储映射层。
 *
 * 关键词存两张表（[MySqlStorage.SCHEMA]）：
 * - `tags`：注册表（name 唯一）——无重复存储、可统计，[findByTags] 走索引 JOIN
 * - `memory_tags`：m:n 关联（FK 级联，删记忆自动清关联）
 *
 * 写入（save/saveAll/json 导入）在**同一事务**里完成「行 + 关键词关联」，
 * 不会出现行在关联丢的半截状态；读取两步组装（行 → 批量补 tags），
 * 同一连接保证两步间的一致快照，无 N+1。
 *
 * 写入即落库（`flush()`/`shutdown()` 为 no-op）。
 */
class MySqlMemoryRepository(
    private val storage: MySqlStorage
) : MemoryRepository {

    override fun save(memory: MemoryItem) = withTransaction { conn ->
        storage.update(conn, INSERT, *argsOf(memory))
        storage.syncMemoryTags(conn, memory.id, memory.tags)
    }

    override fun saveAll(memories: List<MemoryItem>) {
        if (memories.isEmpty()) return
        withTransaction { conn ->
            memories.forEach { m ->
                storage.update(conn, INSERT, *argsOf(m))
                storage.syncMemoryTags(conn, m.id, m.tags)
            }
        }
    }

    override fun findById(id: String): MemoryItem? = storage.connection().use { conn ->
        val items = storage.query(conn, "SELECT * FROM memories WHERE id = ?", id, map = ::mapRow)
        attachTags(conn, items).firstOrNull()
    }

    override fun findByUserId(userId: Long): List<MemoryItem> = storage.connection().use { conn ->
        val items = storage.query(
            conn, "SELECT * FROM memories WHERE user_id = ? ORDER BY created_at",
            userId, map = ::mapRow
        )
        attachTags(conn, items)
    }

    /** 按关键词查记忆——JOIN 索引（规范化的核心收益，不再全表内存过滤） */
    override fun findByTags(tags: List<String>): List<MemoryItem> {
        val distinct = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return emptyList()
        val placeholders = distinct.joinToString(",") { "?" }
        return storage.connection().use { conn ->
            val items = storage.query(
                conn,
                "SELECT DISTINCT m.* FROM memories m " +
                    "INNER JOIN memory_tags mt ON mt.memory_id = m.id " +
                    "INNER JOIN tags t ON t.id = mt.tag_id " +
                    "WHERE t.name IN ($placeholders) ORDER BY m.created_at",
                *distinct.toTypedArray(),
                map = ::mapRow
            )
            attachTags(conn, items)
        }
    }

    /** FK ON DELETE CASCADE：删除记忆时 memory_tags 关联自动清理 */
    override fun delete(id: String) {
        storage.update("DELETE FROM memories WHERE id = ?", id)
    }

    override fun findAll(): List<MemoryItem> = storage.connection().use { conn ->
        val items = storage.query(conn, "SELECT * FROM memories ORDER BY created_at", map = ::mapRow)
        attachTags(conn, items)
    }

    override fun flush() {
        // no-op：写入即落库（见 KDoc）
    }

    /** StorageConfig 的 destroyMethod 兜底；短连接无池可关 */
    fun shutdown() {
        // no-op
    }

    /**
     * json 同步：只插缺失行（INSERT IGNORE），且**只为新插入的行**补关键词关联
     *
     * 已存在的行连同其关联原样保留（库为权威）；行与关联在同一事务内提交。
     *
     * @return (新插入, 已存在跳过)
     */
    internal fun importMissing(rows: List<MemoryItem>): Pair<Int, Int> {
        if (rows.isEmpty()) return 0 to 0
        return storage.connection().use { conn ->
            conn.autoCommit = false
            try {
                var inserted = 0
                val insertedRows = mutableListOf<MemoryItem>()
                conn.prepareStatement(INSERT_IGNORE).use { ps ->
                    for (m in rows) {
                        argsOf(m).forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                        if (ps.executeUpdate() > 0) {
                            inserted++
                            insertedRows += m
                        }
                    }
                }
                insertedRows.forEach { storage.syncMemoryTags(conn, it.id, it.tags) }
                conn.commit()
                inserted to (rows.size - inserted)
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    /** 同一连接内的写事务（行 + 关键词关联原子提交） */
    private fun withTransaction(block: (Connection) -> Unit) {
        storage.connection().use { conn ->
            conn.autoCommit = false
            try {
                block(conn)
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    /** 批量补齐关键词（同一连接：两步读取保持同一快照，无 N+1） */
    private fun attachTags(conn: Connection, items: List<MemoryItem>): List<MemoryItem> {
        if (items.isEmpty()) return items
        val placeholders = items.joinToString(",") { "?" }
        val pairs = storage.query(
            conn,
            "SELECT mt.memory_id, t.name FROM memory_tags mt " +
                "INNER JOIN tags t ON t.id = mt.tag_id WHERE mt.memory_id IN ($placeholders)",
            *items.map { it.id }.toTypedArray(),
            map = { it.getString(1) to it.getString(2) }
        )
        val byMemory = pairs.groupBy({ it.first }, { it.second })
        return items.map { it.copy(tags = byMemory[it.id].orEmpty()) }
    }

    private fun argsOf(m: MemoryItem): Array<Any?> = arrayOf(
        m.id, m.userId, m.subjectType.name, m.type.name, m.content,
        storage.mapper.writeValueAsString(m.relatedUserIds),
        m.strength, m.createdAt
    )

    /** tags 不在行里（规范化到关联表），由 [attachTags] 统一补齐 */
    private fun mapRow(rs: ResultSet): MemoryItem {
        val id = rs.getString("id")
        return MemoryItem(
            id = id,
            userId = rs.getLong("user_id"),
            subjectType = enumOr(rs.getString("subject_type"), MemorySubject.Type.USER),
            type = enumOr(rs.getString("type"), MemoryItem.MemoryType.FACT),
            content = rs.getString("content"),
            relatedUserIds = readLongList(rs.getString("related_user_ids")),
            strength = rs.getDouble("strength"),
            createdAt = rs.getLong("created_at")
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, fallback: E): E =
        runCatching { enumValueOf<E>(raw ?: "") }.getOrDefault(fallback)

    private fun readLongList(raw: String?): List<Long> =
        runCatching { storage.mapper.readValue(raw.orEmpty(), Array<Long>::class.java).toList() }
            .getOrDefault(emptyList())

    companion object {
        /** 行 upsert（关键词不在此——由 syncMemoryTags 写关联表） */
        internal const val INSERT =
            "INSERT INTO memories (id, user_id, subject_type, type, content, related_user_ids, " +
                "strength, created_at) VALUES (?,?,?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE user_id=VALUES(user_id), subject_type=VALUES(subject_type), " +
                "type=VALUES(type), content=VALUES(content), related_user_ids=VALUES(related_user_ids), " +
                "strength=VALUES(strength), created_at=VALUES(created_at)"

        /** json 同步用：主键冲突即跳过——由 [INSERT] 派生防漂移 */
        internal val INSERT_IGNORE: String
            get() = INSERT.substringBefore(" ON DUPLICATE KEY UPDATE")
                .replaceFirst("INSERT ", "INSERT IGNORE ")
    }
}

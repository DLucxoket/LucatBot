package org.dlucxoket.lucatbot.bot.storage.mysql

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.todo.TodoItem
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * MySQL 存储配置（宿主/端口/库名/账号）
 *
 * 由 StorageConfig 从 `storage.mysql.*` 属性组装；连接串参数集中在这里，
 * 仓库实现只拿 [MySqlStorage] 干活。
 *
 * @param importFromJson 是否在**每次启动**时把 data 目录的 json 行导入本库——
 *        只插缺失的行（`INSERT IGNORE`，已存在不覆盖）：json 是迁移来源，
 *        MySQL 是权威源，旧 json 不会把库里已更新的行改回去；
 *        生产开启；测试按需关闭
 */
data class MySqlConfig(
    val host: String = "localhost",
    val port: Int = 3306,
    val database: String = "LucatBot",
    val username: String = "root",
    val password: String = "123456",
    val importFromJson: Boolean = true
) {
    /** 不带库名的服务级连接（建库用） */
    val serverUrl: String =
        "jdbc:mysql://$host:$port/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"

    /** 带库名的业务连接 */
    val databaseUrl: String =
        "jdbc:mysql://$host:$port/$database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
}

/**
 * MySQL 连接与建库建表（存储后端的引导与公共 JDBC 助手）
 *
 * **职责边界**：本类只管「连得上、表在、结构演进、json 同步、通用 SQL 执行」；
 * 每张表的 CRUD 归各 MySQL*Repository（实现既有 Repository 接口，
 * 业务层无感知——DIP：Service 依赖接口，StorageConfig 按 storage.type 绑定实现）。
 *
 * 连接模型：**每次操作短连接**（`use` 自动关闭）。写入即落库（无防抖），
 * 机器人存储量级（本地 MySQL、低频写）完全够用，不引入连接池的复杂度。
 *
 * **记忆关键词规范化**（m:n 两表）：
 * - `tags`：关键词注册表（name 唯一）——按词查询走索引，词条可统计、无重复存储
 * - `memory_tags`：记忆 ↔ 关键词 关联表（FK 级联：删记忆自动清关联）
 *
 * 引导流程（[init]，StorageConfig 启动时调用一次，幂等）：
 * 1. 服务级连接 `CREATE DATABASE IF NOT EXISTS`
 * 2. **旧结构检测**：memories 若还带旧 `tags` 列 → 读出数据 → DROP（结构演进
 *    模式：读出 → 重建 → 回填，数据无损）
 * 3. 库级连接建五张表（`IF NOT EXISTS`）→ 回填旧结构数据（tags 列 → 关联表）
 * 4. [MySqlConfig.importFromJson] 开启时，**每次启动**把 json 中缺失的行导入
 *    （`INSERT IGNORE`——已存在的行不覆盖，MySQL 为权威源）
 */
class MySqlStorage(private val config: MySqlConfig) {

    private val log = LoggerFactory.getLogger(MySqlStorage::class.java)

    /** 共享 ObjectMapper（json 同步与 related_user_ids 等列表字段序列化用） */
    val mapper = jacksonObjectMapper()

    @Volatile
    private var initialized = false

    /**
     * 引导：建库 → 结构演进 → 建表 → 回填 → json 同步（幂等，可重复调用）
     */
    @Synchronized
    fun init() {
        if (initialized) return

        DriverManager.getConnection(config.serverUrl, config.username, config.password).use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE DATABASE IF NOT EXISTS `${config.database}` " +
                        "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
                )
            }
        }
        connection().use { conn ->
            // 旧结构（memories.tags 列）→ 读出、DROP（必须在建关联表之前，否则 FK 依赖挡住）
            val legacy = drainLegacyMemories(conn)
            conn.createStatement().use { st -> SCHEMA.forEach { st.executeUpdate(it) } }
            if (legacy.isNotEmpty()) restoreLegacyMemories(conn, legacy)
        }
        if (config.importFromJson) {
            importFromJson()
        }
        initialized = true
        log.info("MySQL 存储就绪：{}@{}:{}/{}", config.username, config.host, config.port, config.database)
    }

    /** 业务连接（调用方用完 `use` 关闭） */
    fun connection(): Connection =
        DriverManager.getConnection(config.databaseUrl, config.username, config.password)

    /** 执行更新/DDL，返回受影响行数 */
    fun update(sql: String, vararg params: Any?): Int = connection().use { conn ->
        update(conn, sql, *params)
    }

    /** 同连接上执行更新（多语句事务内用） */
    fun update(conn: Connection, sql: String, vararg params: Any?): Int =
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeUpdate()
        }

    /** 批量执行同一 SQL（单连接 + 事务 + addBatch；要么全成要么全回滚）。
     *  返回每行受影响行数（INSERT IGNORE 下 1=新插入、0=已存在跳过） */
    fun batchUpdate(sql: String, rows: List<Array<Any?>>): IntArray = connection().use { conn ->
        conn.autoCommit = false
        try {
            val counts = executeBatch(conn, sql, rows)
            conn.commit()
            counts
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    /** 同连接上批量执行（事务内用，不提交） */
    fun executeBatch(conn: Connection, sql: String, rows: List<Array<Any?>>): IntArray =
        conn.prepareStatement(sql).use { ps ->
            rows.forEach { row ->
                row.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                ps.addBatch()
            }
            ps.executeBatch()
        }

    /** 查询并逐行映射（连接随 use 关闭） */
    fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
        connection().use { conn -> query(conn, sql, *params, map = map) }

    /** 同连接上查询（多语句读取保持同一快照，如记忆 + 关键词两步组装） */
    fun <T> query(conn: Connection, sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out += map(rs)
                out
            }
        }

    /** 单值查询（COUNT 等） */
    fun <T> queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
        query(sql, *params, map = map).firstOrNull()

    // ------------------------------------------------------------------
    // 关键词规范化：tags 注册表 + memory_tags 关联表
    // ------------------------------------------------------------------

    /**
     * 确保关键词存在并返回其 id（注册表幂等插入：INSERT IGNORE + 查 id）
     *
     * 同连接执行，供记忆写入/导入的事务复用。
     */
    fun ensureTagId(conn: Connection, name: String): Int {
        update(conn, "INSERT IGNORE INTO tags (name) VALUES (?)", name)
        return conn.prepareStatement("SELECT id FROM tags WHERE name = ?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1)
                else error("关键词注册失败（name=$name）")
            }
        }
    }

    /**
     * 重建某条记忆的关键词关联（先清后写，幂等）
     *
     * 同连接执行，供仓库的写入事务复用；FK ON DELETE CASCADE 保证
     * 删记忆时关联自动清理。
     */
    fun syncMemoryTags(conn: Connection, memoryId: String, tags: List<String>) {
        update(conn, "DELETE FROM memory_tags WHERE memory_id = ?", memoryId)
        val distinct = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return
        conn.prepareStatement("INSERT INTO memory_tags (memory_id, tag_id) VALUES (?, ?)").use { ps ->
            distinct.forEach { tag ->
                ps.setString(1, memoryId)
                ps.setInt(2, ensureTagId(conn, tag))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    // ------------------------------------------------------------------
    // 旧结构演进：memories.tags 列 → tags/memory_tags 关联表（数据无损）
    // ------------------------------------------------------------------

    private data class LegacyMemory(val values: Array<Any?>, val tags: List<String>)

    /** 检测旧 tags 列：有则读出全部行并 DROP（须在建 FK 关联表之前） */
    private fun drainLegacyMemories(conn: Connection): List<LegacyMemory> {
        val hasLegacy = conn.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'memories' AND COLUMN_NAME = 'tags'"
        ).use { ps ->
            ps.setString(1, config.database)
            ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
        if (!hasLegacy) return emptyList()

        val legacy = conn.prepareStatement(
            "SELECT id, user_id, subject_type, type, content, related_user_ids, tags, strength, created_at FROM memories"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<LegacyMemory>()
                while (rs.next()) {
                    // 列序：id, user_id, subject_type, type, content, related_user_ids, tags, strength, created_at
                    val tags = runCatching { mapper.readValue<List<String>>(rs.getString("tags") ?: "[]") }
                        .getOrDefault(emptyList())
                    out += LegacyMemory(
                        values = arrayOf(
                            rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getDouble(8), rs.getLong(9)
                        ),
                        tags = tags
                    )
                }
                out
            }
        }
        conn.createStatement().use { it.executeUpdate("DROP TABLE memories") }
        log.info("检测到旧版 memories.tags 列：读出 {} 条，重建为 tags/memory_tags 规范化结构", legacy.size)
        return legacy
    }

    /** 回填旧结构数据（行 + 关键词关联），全部在建好的新表上执行 */
    private fun restoreLegacyMemories(conn: Connection, legacy: List<LegacyMemory>) {
        legacy.forEach { row ->
            update(conn, MySqlMemoryRepository.INSERT, *row.values)
            syncMemoryTags(conn, row.values[0] as String, row.tags)
        }
        log.info("旧结构回填完成：{} 条记忆及其关键词", legacy.size)
    }

    // ------------------------------------------------------------------
    // json → 本库同步（每次启动；只补缺失行，已存在不覆盖）
    // ------------------------------------------------------------------

    /**
     * 把 data 目录 json 里的行导入本库
     *
     * 语义：**只插缺失**（`INSERT IGNORE` 主键冲突即跳过）——json 是来源、
     * 本库是权威：库里已更新的行（如待办已勾完成）不会被旧 json 改回去；
     * json 里有而库里没有的行（历史存量/别处产出）每次启动都会补进来。
     */
    private fun importFromJson() {
        importFile<ChatMessageRecord>("聊天记录", "data/chat-history.json",
            MySqlChatHistoryRepository.INSERT_IGNORE) { r ->
            arrayOf(
                r.id, r.conversationId, r.senderId, r.senderName, r.content,
                r.messageType.name, r.timestamp, r.messageId, r.isFromBot
            )
        }
        // 记忆走仓库（行 + 关键词关联同事务）
        readJson<MemoryItem>("data/memories.json", "记忆")
            ?.let { rows ->
                val (inserted, skipped) = MySqlMemoryRepository(this).importMissing(rows)
                log.info(
                    "从 data/memories.json 同步记忆：新插入 {} 条、已存在跳过 {} 条（共 {}，关键词经 tags/memory_tags）",
                    inserted, skipped, rows.size
                )
            }
        importFile<TodoItem>("待办", "data/todos.json",
            MySqlTodoRepository.INSERT_IGNORE) { t ->
            arrayOf(t.id, t.content, t.createdAt, t.done, t.conversationId)
        }
    }

    /** 读 json 列表（文件不存在/解析失败 → null 并记日志） */
    internal inline fun <reified T> readJson(jsonPath: String, label: String): List<T>? {
        val file = java.io.File(jsonPath)
        if (!file.exists()) return null
        val rows = runCatching { mapper.readValue<List<T>>(file) }
            .getOrElse { e ->
                log.error("{}同步：解析 {} 失败，跳过（{}）", label, jsonPath, e.message)
                null
            }
        if (rows != null && rows.isEmpty()) return null
        return rows
    }

    private inline fun <reified T> importFile(
        label: String,
        jsonPath: String,
        sql: String,
        rowOf: (T) -> Array<Any?>
    ) {
        val rows = readJson<T>(jsonPath, label) ?: return
        val counts = batchUpdate(sql, rows.map(rowOf))
        val inserted = counts.count { it > 0 }
        log.info(
            "从 {} 同步{}：新插入 {} 条、已存在跳过 {} 条（共 {}）",
            jsonPath, label, inserted, rows.size - inserted, rows.size
        )
    }

    private companion object {
        /**
         * 建表 DDL（幂等）：五张表——三个 Repository 各一张 +
         * 关键词规范化两张（tags 注册表、memory_tags 关联表）
         */
        val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS chat_history (
                id VARCHAR(64) NOT NULL PRIMARY KEY,
                conversation_id VARCHAR(64) NOT NULL,
                sender_id BIGINT NOT NULL DEFAULT 0,
                sender_name VARCHAR(128) NOT NULL DEFAULT '',
                content TEXT NOT NULL,
                message_type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
                timestamp_ms BIGINT NOT NULL,
                message_id BIGINT NULL,
                is_from_bot BOOLEAN NOT NULL DEFAULT FALSE,
                KEY idx_conv_time (conversation_id, timestamp_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS memories (
                id VARCHAR(64) NOT NULL PRIMARY KEY,
                user_id BIGINT NOT NULL,
                subject_type VARCHAR(16) NOT NULL DEFAULT 'USER',
                type VARCHAR(16) NOT NULL,
                content TEXT NOT NULL,
                related_user_ids VARCHAR(2048) NOT NULL DEFAULT '[]',
                strength DOUBLE NOT NULL DEFAULT 1.0,
                created_at BIGINT NOT NULL,
                KEY idx_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS tags (
                id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                UNIQUE KEY uk_name (name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS memory_tags (
                memory_id VARCHAR(64) NOT NULL,
                tag_id INT NOT NULL,
                PRIMARY KEY (memory_id, tag_id),
                KEY idx_tag (tag_id),
                CONSTRAINT fk_memory_tags_memory FOREIGN KEY (memory_id)
                    REFERENCES memories (id) ON DELETE CASCADE,
                CONSTRAINT fk_memory_tags_tag FOREIGN KEY (tag_id)
                    REFERENCES tags (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS todos (
                id VARCHAR(64) NOT NULL PRIMARY KEY,
                content TEXT NOT NULL,
                created_at BIGINT NOT NULL,
                done BOOLEAN NOT NULL DEFAULT FALSE,
                conversation_id VARCHAR(64) NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent()
        )
    }
}

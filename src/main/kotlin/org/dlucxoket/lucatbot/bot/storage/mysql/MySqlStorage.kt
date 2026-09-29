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
 * **时间列用 DATETIME(3)**（毫秒精度）：领域模型保持 `Long` epoch 毫秒，
 * 仓库层经 [MySqlTime] 用 JDBC Timestamp 对称转换（同 JVM 写读往返无损毫秒）；
 * 旧 BIGINT 毫秒数据在启动时经「读出 → 重建 → 回填」转换（见下）。
 *
 * **记忆关键词规范化**（m:n 两表）：
 * - `tags`：关键词注册表（name 唯一）——按词查询走索引，词条可统计、无重复存储
 * - `memory_tags`：记忆 ↔ 关键词 关联表（FK 级联：删记忆自动清关联）
 *
 * 引导流程（[init]，StorageConfig 启动时调用一次，幂等）：
 * 1. 服务级连接 `CREATE DATABASE IF NOT EXISTS`
 * 2. **结构演进检测**（information_schema）：
 *    - 时间列仍是 BIGINT（epoch 毫秒）→ 读出行数据 → DROP → 重建为 DATETIME(3) 并回填转换
 *    - memories 还带旧 `tags` 列 → 读出 → DROP → 关键词回填到关联表
 *    （DROP memories 时置 FOREIGN_KEY_CHECKS=0：memory_tags 的 FK 行
 *     在重建后 id 不变、自动恢复有效，无需搬动）
 * 3. 库级连接建五张表（`IF NOT EXISTS`）→ 回填旧结构数据
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
            // 旧结构 → 读出 + DROP（须在建表/建关联表之前；DROP memories 关掉 FK 检查）
            val legacy = drainLegacy(conn)
            conn.createStatement().use { st -> SCHEMA.forEach { st.executeUpdate(it) } }
            // 加列演进：老库已有 chat_history 但缺 sender_nickname（IF NOT EXISTS 不会补列）
            ensureColumn(conn, "chat_history", "sender_nickname", "sender_nickname VARCHAR(128) NULL")
            if (legacy != null) restoreLegacy(conn, legacy)
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
    // 结构演进：BIGINT 时间列 → DATETIME(3)；旧 memories.tags 列 → 关联表
    // ------------------------------------------------------------------

    /** chat_history 旧行（timestamp_ms 为 BIGINT epoch 毫秒） */
    private class LegacyChat(
        val id: String, val conv: String, val senderId: Long, val senderName: String,
        val content: String, val type: String, val tsMs: Long, val messageId: Long?, val isBot: Boolean
    )

    /** todos 旧行（created_at 为 BIGINT epoch 毫秒） */
    private class LegacyTodo(
        val id: String, val content: String, val createdMs: Long,
        val done: Boolean, val convId: String?
    )

    /** memories 旧行；[tagsFromColumn] 非空 = 还带旧 tags 列（更早的结构） */
    private class LegacyMemory(
        val id: String, val userId: Long, val subject: String, val type: String,
        val content: String, val relatedJson: String, val strength: Double,
        val createdMs: Long, val tagsFromColumn: List<String>?
    )

    private class LegacySnapshot(
        val chat: List<LegacyChat>?,
        val memories: List<LegacyMemory>?,
        val todos: List<LegacyTodo>?
    )

    /** 列的 DATA_TYPE（information_schema；表/列不存在 → null） */
    private fun columnType(conn: Connection, table: String, column: String): String? =
        conn.prepareStatement(
            "SELECT DATA_TYPE FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?"
        ).use { ps ->
            ps.setString(1, config.database)
            ps.setString(2, table)
            ps.setString(3, column)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1).lowercase() else null }
        }

    /**
     * 加列演进：老库的表已存在（`CREATE TABLE IF NOT EXISTS` 不会补列）时，
     * 启动时把新增列 ALTER 补上（幂等：列已存在则零开销跳过）。
     *
     * @param definition ADD COLUMN 的完整列定义（如 `sender_nickname VARCHAR(128) NULL`）
     */
    private fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
        if (columnType(conn, table, column) != null) return
        update(conn, "ALTER TABLE `$table` ADD COLUMN $definition")
        log.info("结构演进：{}.{} 列缺失，已补充", table, column)
    }

    /**
     * 检测旧结构：有则读出全部行并 DROP（须在建表之前；DROP memories 关 FK 检查，
     * memory_tags 的关联行在表重建后 id 不变、自动恢复有效）
     *
     * @return null = 无旧结构（常规幂等路径，零开销）
     */
    private fun drainLegacy(conn: Connection): LegacySnapshot? {
        val chatLegacy = columnType(conn, "chat_history", "timestamp_ms") == "bigint"
        val todoLegacy = columnType(conn, "todos", "created_at") == "bigint"
        val memCreatedLegacy = columnType(conn, "memories", "created_at") == "bigint"
        val memTagsLegacy = columnType(conn, "memories", "tags") != null
        if (!chatLegacy && !todoLegacy && !memCreatedLegacy && !memTagsLegacy) return null

        val chat = if (chatLegacy) conn.prepareStatement(
            "SELECT id, conversation_id, sender_id, sender_name, content, message_type, " +
                "timestamp_ms, message_id, is_from_bot FROM chat_history"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<LegacyChat>()
                while (rs.next()) {
                    out += LegacyChat(
                        rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7),
                        rs.getLong(8).let { if (rs.wasNull()) null else it },
                        rs.getBoolean(9)
                    )
                }
                out
            }
        } else null

        val memories = if (memCreatedLegacy || memTagsLegacy) {
            // 列序：1..6 = id..related_user_ids；[7 = tags（仅旧结构）]；末两列 = strength, created_at
            val sql = buildString {
                append("SELECT id, user_id, subject_type, type, content, related_user_ids, ")
                if (memTagsLegacy) append("tags, ")
                append("strength, created_at FROM memories")
            }
            conn.prepareStatement(sql).use { ps ->
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<LegacyMemory>()
                    while (rs.next()) {
                        val tagsCol: List<String>? = if (memTagsLegacy) {
                            runCatching { mapper.readValue<List<String>>(rs.getString("tags") ?: "[]") }
                                .getOrDefault(emptyList())
                        } else null
                        out += LegacyMemory(
                            id = rs.getString(1), userId = rs.getLong(2),
                            subject = rs.getString(3), type = rs.getString(4),
                            content = rs.getString(5), relatedJson = rs.getString(6) ?: "[]",
                            strength = rs.getDouble(if (memTagsLegacy) 8 else 7),
                            createdMs = rs.getLong(if (memTagsLegacy) 9 else 8),
                            tagsFromColumn = tagsCol
                        )
                    }
                    out
                }
            }
        } else null

        val todos = if (todoLegacy) conn.prepareStatement(
            "SELECT id, content, created_at, done, conversation_id FROM todos"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<LegacyTodo>()
                while (rs.next()) {
                    out += LegacyTodo(
                        rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getBoolean(4), rs.getString(5)
                    )
                }
                out
            }
        } else null

        conn.createStatement().use { st ->
            st.executeUpdate("SET FOREIGN_KEY_CHECKS = 0")
        }
        try {
            conn.createStatement().use { st ->
                if (chatLegacy) st.executeUpdate("DROP TABLE chat_history")
                if (memCreatedLegacy || memTagsLegacy) st.executeUpdate("DROP TABLE memories")
                if (todoLegacy) st.executeUpdate("DROP TABLE todos")
            }
        } finally {
            conn.createStatement().use { st ->
                st.executeUpdate("SET FOREIGN_KEY_CHECKS = 1")
            }
        }
        log.info(
            "结构演进：检测到旧时间列/tags 列，读出待回填 chat={} memories={} todos={}",
            chat?.size ?: "-", memories?.size ?: "-", todos?.size ?: "-"
        )
        return LegacySnapshot(chat, memories, todos)
    }

    /** 回填旧结构数据（BIGINT 毫秒 → DATETIME(3) 经 [MySqlTime] 对称转换） */
    private fun restoreLegacy(conn: Connection, legacy: LegacySnapshot) {
        legacy.chat?.forEach { c ->
            update(
                conn, MySqlChatHistoryRepository.INSERT,
                c.id, c.conv, c.senderId, c.senderName, null, c.content,
                c.type, MySqlTime.of(c.tsMs), c.messageId, c.isBot
            )
        }
        legacy.memories?.forEach { m ->
            update(
                conn, MySqlMemoryRepository.INSERT,
                m.id, m.userId, m.subject, m.type, m.content,
                m.relatedJson, m.strength, MySqlTime.of(m.createdMs)
            )
            m.tagsFromColumn?.let { tags -> syncMemoryTags(conn, m.id, tags) }
        }
        legacy.todos?.forEach { t ->
            update(
                conn, MySqlTodoRepository.INSERT,
                t.id, t.content, MySqlTime.of(t.createdMs), t.done, t.convId
            )
        }
        log.info(
            "结构演进回填完成：chat={} memories={} todos={}",
            legacy.chat?.size ?: "-", legacy.memories?.size ?: "-", legacy.todos?.size ?: "-"
        )
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
                r.id, r.conversationId, r.senderId, r.senderName, r.senderNickname, r.content,
                r.messageType.name, MySqlTime.of(r.timestamp), r.messageId, r.isFromBot
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
            arrayOf(t.id, t.content, MySqlTime.of(t.createdAt), t.done, t.conversationId)
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
         *
         * 时间列统一 **DATETIME(3)**（毫秒精度，配合 [MySqlTime] 对称转换）。
         */
        val SCHEMA = listOf(
            """
            CREATE TABLE IF NOT EXISTS chat_history (
                id VARCHAR(64) NOT NULL PRIMARY KEY,
                conversation_id VARCHAR(64) NOT NULL,
                sender_id BIGINT NOT NULL DEFAULT 0,
                sender_name VARCHAR(128) NOT NULL DEFAULT '',
                sender_nickname VARCHAR(128) NULL,
                content TEXT NOT NULL,
                message_type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
                sent_at DATETIME(3) NOT NULL,
                message_id BIGINT NULL,
                is_from_bot BOOLEAN NOT NULL DEFAULT FALSE,
                KEY idx_conv_time (conversation_id, sent_at)
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
                created_at DATETIME(3) NOT NULL,
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
                created_at DATETIME(3) NOT NULL,
                done BOOLEAN NOT NULL DEFAULT FALSE,
                conversation_id VARCHAR(64) NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent()
        )
    }
}

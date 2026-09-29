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
 * @param migrateFromJson 建表后若表为空且 data 目录下存在对应 json 文件，是否一次性
 *        迁入存量数据（生产开启；测试库关闭）
 */
data class MySqlConfig(
    val host: String = "localhost",
    val port: Int = 3306,
    val database: String = "LucatBot",
    val username: String = "root",
    val password: String = "123456",
    val migrateFromJson: Boolean = true
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
 * **职责边界**：本类只管「连得上、表在、JSON 存量迁入、通用 SQL 执行」；
 * 每张表的 CRUD 归各 MySQL*Repository（实现既有 Repository 接口，
 * 业务层无感知——DIP：Service 依赖接口，StorageConfig 按 storage.type 绑定实现）。
 *
 * 连接模型：**每次操作短连接**（`use` 自动关闭）。写入即落库（无防抖），
 * 机器人存储量级（本地 MySQL、低频写）完全够用，不引入连接池的复杂度。
 *
 * 引导流程（[init]，StorageConfig 启动时调用一次，幂等）：
 * 1. 服务级连接 `CREATE DATABASE IF NOT EXISTS`
 * 2. 库级连接建三张表（`IF NOT EXISTS`）
 * 3. [migrateFromJson] 时对空表从 data 目录下的 json 文件迁入存量数据
 */
class MySqlStorage(private val config: MySqlConfig) {

    private val log = LoggerFactory.getLogger(MySqlStorage::class.java)

    /** 共享 ObjectMapper（仓库序列化 tags 等列表字段用） */
    val mapper = jacksonObjectMapper()

    @Volatile
    private var initialized = false

    /**
     * 引导：建库 → 建表 → JSON 存量迁移（幂等，可重复调用）
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
            conn.createStatement().use { st ->
                SCHEMA.forEach { st.executeUpdate(it) }
            }
        }
        if (config.migrateFromJson) {
            migrateFromJsonIfEmpty()
        }
        initialized = true
        log.info("MySQL 存储就绪：{}@{}:{}/{}", config.username, config.host, config.port, config.database)
    }

    /** 业务连接（调用方用完 `use` 关闭） */
    fun connection(): Connection =
        DriverManager.getConnection(config.databaseUrl, config.username, config.password)

    /** 执行更新/DDL，返回受影响行数 */
    fun update(sql: String, vararg params: Any?): Int = connection().use { conn ->
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeUpdate()
        }
    }

    /** 批量执行同一 SQL（单连接 + 事务 + addBatch；要么全成要么全回滚，迁移/批量保存用） */
    fun batchUpdate(sql: String, rows: List<Array<Any?>>): Unit = connection().use { conn ->
        conn.autoCommit = false
        try {
            conn.prepareStatement(sql).use { ps ->
                rows.forEach { row ->
                    row.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    /** 查询并逐行映射（连接随 use 关闭） */
    fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
        connection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<T>()
                    while (rs.next()) out += map(rs)
                    out
                }
            }
        }

    /** 单值查询（COUNT 等） */
    fun <T> queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
        query(sql, *params, map = map).firstOrNull()

    // ------------------------------------------------------------------
    // JSON 存量迁移（表为空 + 文件存在才导；幂等）
    // ------------------------------------------------------------------

    private fun migrateFromJsonIfEmpty() {
        importIfEmpty<ChatMessageRecord>(
            label = "聊天记录", jsonPath = "data/chat-history.json",
            countSql = "SELECT COUNT(*) AS c FROM chat_history"
        ) { rows ->
            batchUpdate(MySqlChatHistoryRepository.INSERT, rows.map { r ->
                arrayOf<Any?>(
                    r.id, r.conversationId, r.senderId, r.senderName, r.content,
                    r.messageType.name, r.timestamp, r.messageId, r.isFromBot
                )
            })
        }
        importIfEmpty<MemoryItem>(
            label = "记忆", jsonPath = "data/memories.json",
            countSql = "SELECT COUNT(*) AS c FROM memories"
        ) { rows ->
            batchUpdate(MySqlMemoryRepository.INSERT, rows.map { m ->
                arrayOf<Any?>(
                    m.id, m.userId, m.subjectType.name, m.type.name, m.content,
                    mapper.writeValueAsString(m.relatedUserIds),
                    mapper.writeValueAsString(m.tags),
                    m.strength, m.createdAt
                )
            })
        }
        importIfEmpty<TodoItem>(
            label = "待办", jsonPath = "data/todos.json",
            countSql = "SELECT COUNT(*) AS c FROM todos"
        ) { rows ->
            batchUpdate(MySqlTodoRepository.INSERT, rows.map { t ->
                arrayOf<Any?>(t.id, t.content, t.createdAt, t.done, t.conversationId)
            })
        }
    }

    private inline fun <reified T> importIfEmpty(
        label: String,
        jsonPath: String,
        countSql: String,
        insert: (List<T>) -> Unit
    ) {
        val count = queryOne(countSql, map = { rs -> rs.getLong("c") }) ?: 0L
        if (count > 0) return
        val file = java.io.File(jsonPath)
        if (!file.exists()) return
        val rows = runCatching { mapper.readValue<List<T>>(file) }
            .getOrElse { e ->
                log.error("{}迁移：解析 {} 失败，跳过（{}）", label, jsonPath, e.message)
                return
            }
        if (rows.isEmpty()) return
        insert(rows)
        log.info("已从 {} 迁移 {} 条{}到 MySQL", jsonPath, rows.size, label)
    }

    private companion object {
        /** 建表 DDL（幂等）：三张表对应三个 Repository */
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
                tags VARCHAR(1024) NOT NULL DEFAULT '[]',
                strength DOUBLE NOT NULL DEFAULT 1.0,
                created_at BIGINT NOT NULL,
                KEY idx_user (user_id)
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

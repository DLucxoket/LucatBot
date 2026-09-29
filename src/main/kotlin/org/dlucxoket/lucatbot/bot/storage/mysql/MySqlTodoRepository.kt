package org.dlucxoket.lucatbot.bot.storage.mysql

import org.dlucxoket.lucatbot.bot.todo.TodoItem
import org.dlucxoket.lucatbot.bot.todo.TodoRepository
import java.sql.ResultSet

/**
 * 待办仓库——MySQL 实现
 *
 * 与 [org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository] 实现同一接口，
 * StorageConfig 按 `storage.type` 绑定，业务层无感知。
 * 写入即落库（`flush()`/`shutdown()` 为 no-op）。
 */
class MySqlTodoRepository(
    private val storage: MySqlStorage
) : TodoRepository {

    override fun save(todo: TodoItem) {
        storage.update(INSERT, todo.id, todo.content, todo.createdAt, todo.done, todo.conversationId)
    }

    override fun findAll(): List<TodoItem> =
        storage.query("SELECT * FROM todos ORDER BY created_at", map = ::mapRow)

    override fun flush() {
        // no-op：写入即落库（见 KDoc）
    }

    /** StorageConfig 的 destroyMethod 兜底；短连接无池可关 */
    fun shutdown() {
        // no-op
    }

    private fun mapRow(rs: ResultSet) = TodoItem(
        id = rs.getString("id"),
        content = rs.getString("content"),
        createdAt = rs.getLong("created_at"),
        done = rs.getBoolean("done"),
        conversationId = rs.getString("conversation_id")
    )

    companion object {
        internal const val INSERT =
            "INSERT INTO todos (id, content, created_at, done, conversation_id) VALUES (?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE content=VALUES(content), created_at=VALUES(created_at), " +
                "done=VALUES(done), conversation_id=VALUES(conversation_id)"
    }
}

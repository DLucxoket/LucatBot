package org.dlucxoket.lucatbot.bot.todo

/**
 * 待办仓库（存储抽象）
 *
 * **OOP 设计原则**：
 * - 依赖倒置（DIP）：TodoService 依赖此接口，不依赖具体实现
 * - 开闭原则（OCP）：新增存储方式（SQLite/Redis）只需实现此接口
 * - 单一职责（SRP）：只管存取，不管业务逻辑
 *
 * 当前实现：[JsonFileTodoRepository]（JSON 文件）
 */
interface TodoRepository {

    /**
     * 保存一条待办（新增或更新）
     */
    fun save(todo: TodoItem)

    /**
     * 批量保存
     */
    fun saveAll(todos: List<TodoItem>)

    /**
     * 获取所有待办（含已完成）
     */
    fun findAll(): List<TodoItem>

    /**
     * 删除一条待办
     */
    fun delete(id: String)

    /**
     * 持久化（立即写入存储）
     */
    fun flush()
}

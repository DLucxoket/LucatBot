package org.dlucxoket.lucatbot.bot.todo

/**
 * 待办仓库（存储抽象）
 *
 * **OOP 设计原则**：
 * - 依赖倒置（DIP）：TodoService 依赖此接口，不依赖具体实现
 * - 开闭原则（OCP）：新增存储方式（SQLite/Redis）只需实现此接口
 * - 接口隔离（ISP）：只保留 TodoService 实际调用的存查方法
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
     * 删除一条待办（**取消**——与 [save] 的「标完成」不同：从清单里**移除**）
     *
     * 取消后既不进「最近已完成」、也**不再注入 prompt**——陈年待办堆着只会
     * 白烧 token，还会稀释「待办 = 现在该干的事」这个行动理由信号
     *
     * @param id 精确 id；不存在时静默 no-op（幂等）
     */
    fun delete(id: String)

    /**
     * 获取所有待办（含已完成）
     */
    fun findAll(): List<TodoItem>

    /**
     * 持久化（立即写入存储）
     */
    fun flush()
}

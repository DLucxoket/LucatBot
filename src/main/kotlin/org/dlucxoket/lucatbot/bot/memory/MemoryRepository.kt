package org.dlucxoket.lucatbot.bot.memory

/**
 * 记忆仓库（存储抽象）
 *
 * **OOP 设计原则**：
 * - 依赖倒置（DIP）：MemoryService 依赖此接口，不依赖具体实现
 * - 开闭原则（OCP）：新增存储方式（SQLite/Redis）只需实现此接口
 * - 单一职责（SRP）：只管存取，不管业务逻辑
 *
 * 当前实现：[JsonFileMemoryRepository]（JSON 文件）
 * 未来可迁移：SQLite、Redis 等
 */
interface MemoryRepository {

    /**
     * 保存一条记忆（新增或更新）
     */
    fun save(memory: MemoryItem)

    /**
     * 批量保存
     */
    fun saveAll(memories: List<MemoryItem>)

    /**
     * 按 ID 查找
     */
    fun findById(id: String): MemoryItem?

    /**
     * 按用户查找所有记忆
     */
    fun findByUserId(userId: Long): List<MemoryItem>

    /**
     * 按标签查找
     */
    fun findByTags(tags: List<String>): List<MemoryItem>

    /**
     * 删除一条记忆
     */
    fun delete(id: String)

    /**
     * 获取所有记忆
     */
    fun findAll(): List<MemoryItem>

    /**
     * 持久化（立即写入存储）
     */
    fun flush()
}

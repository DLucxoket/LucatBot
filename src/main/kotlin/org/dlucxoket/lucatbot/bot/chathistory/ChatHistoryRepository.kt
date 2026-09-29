package org.dlucxoket.lucatbot.bot.chathistory

/**
 * 聊天记录仓库（存储抽象）
 *
 * **OOP 设计原则**：
 * - 依赖倒置（DIP）：ChatHistoryService 依赖此接口，不依赖具体实现
 * - 开闭原则（OCP）：新增存储方式只需实现此接口
 * - 接口隔离（ISP）：只保留服务实际调用的存取方法，未读状态由 LucatBot.unreadQueue 负责
 * - 单一职责（SRP）：只管存取，不管业务逻辑
 *
 * 当前实现：[JsonFileChatHistoryRepository]
 * 未来可迁移：SQLite、Redis 等
 */
interface ChatHistoryRepository {

    /**
     * 保存一条聊天记录
     */
    fun save(record: ChatMessageRecord)

    /**
     * 按会话查询聊天记录（按时间排序）
     *
     * @param conversationId 会话 ID
     * @param limit 最大条数（0 = 不限）
     * @param before 只返回此时间之前的消息（0 = 不限）
     */
    fun findByConversation(conversationId: String, limit: Int = 0, before: Long = 0): List<ChatMessageRecord>

    /**
     * 全部记录（启动清理 `collapseStoredTwins` 用——按会话分组折叠孪生双份行）
     */
    fun findAll(): List<ChatMessageRecord>

    /**
     * 删除一条记录（启动清理时移除被并掉的孪生行；不存在则为无操作）
     */
    fun delete(id: String)

    /**
     * 持久化
     */
    fun flush()
}

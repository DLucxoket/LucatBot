package org.dlucxoket.lucatbot.bot.chathistory

/**
 * 聊天记录仓库（存储抽象）
 *
 * **OOP 设计原则**：
 * - 依赖倒置（DIP）：ChatHistoryService 依赖此接口，不依赖具体实现
 * - 开闭原则（OCP）：新增存储方式只需实现此接口
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
     * 批量保存
     */
    fun saveAll(records: List<ChatMessageRecord>)

    /**
     * 按会话查询聊天记录（按时间排序）
     *
     * @param conversationId 会话 ID
     * @param limit 最大条数（0 = 不限）
     * @param before 只返回此时间之前的消息（0 = 不限）
     */
    fun findByConversation(conversationId: String, limit: Int = 0, before: Long = 0): List<ChatMessageRecord>

    /**
     * 查询某个会话的未读消息
     */
    fun findUnread(conversationId: String): List<ChatMessageRecord>

    /**
     * 查询所有未读消息（跨会话）
     */
    fun findAllUnread(): List<ChatMessageRecord>

    /**
     * 获取所有有未读的会话 ID
     */
    fun findUnreadConversations(): List<String>

    /**
     * 按发送者查询
     */
    fun findBySender(senderId: Long): List<ChatMessageRecord>

    /**
     * 按 ID 查找
     */
    fun findById(id: String): ChatMessageRecord?

    /**
     * 标记某个会话的所有消息为已读
     */
    fun markAsRead(conversationId: String)

    /**
     * 标记单条消息为已读
     */
    fun markOneAsRead(id: String)

    /**
     * 删除一条记录
     */
    fun delete(id: String)

    /**
     * 清理旧记录（保留最近 N 条）
     */
    fun cleanup(keepPerConversation: Int = 100)

    /**
     * 持久化
     */
    fun flush()
}

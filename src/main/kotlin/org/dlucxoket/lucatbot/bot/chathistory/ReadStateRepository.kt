package org.dlucxoket.lucatbot.bot.chathistory

/**
 * 已读水位仓库（存储抽象）
 *
 * 会话级水位 = 该会话**最后已读消息的时间**（epoch ms）。语义：进会话即看到
 * 全部历史 → 水位推到该会话 max(sent_at)；重启后「未读」= chat_history 中
 * `time > 水位` 的消息（见 ReadStateService 重建）。
 *
 * 此前已读状态纯内存（LucatBot.unreadQueue），重启即丢——本表补上持久化。
 * DIP：ReadStateService 依赖此接口，StorageConfig 按 storage.type 绑定实现。
 */
interface ReadStateRepository {

    /** 该会话的已读水位（epoch ms；无记录 → null） */
    fun lastReadAt(conversationId: String): Long?

    /** 写入/更新水位（进会话即调用；幂等覆盖） */
    fun markRead(conversationId: String, atMs: Long)

    /** 全部会话的水位（重启重建未读用；无记录 → 空表） */
    fun allLastRead(): Map<String, Long>
}

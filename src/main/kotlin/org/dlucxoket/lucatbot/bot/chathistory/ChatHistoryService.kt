package org.dlucxoket.lucatbot.bot.chathistory

import org.dlucxoket.lucatbot.model.event.message.MessageEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 聊天记录服务
 *
 * **模拟真人的聊天记忆**：
 * - 记住每条消息（谁、什么时候、说了什么）
 * - 标记已读/未读（像 QQ 未读红点）
 * - 支持查历史（"上次他说了啥来着？"）
 *
 * **OOP 设计**：
 * - 依赖 [ChatHistoryRepository] 接口（DIP）
 * - 单一职责：聊天记录的存取、查询、已读管理
 */
@Component
class ChatHistoryService(
    private val repository: ChatHistoryRepository
) {
    private val log = LoggerFactory.getLogger(ChatHistoryService::class.java)

    /**
     * 记录一条收到的消息
     */
    fun recordMessage(event: MessageEvent, content: String) {
        val record = ChatMessageRecord(
            conversationId = event.conversationId,
            senderId = event.sender.qqNumber,
            senderName = event.sender.nickname,
            content = content,
            messageType = detectMessageType(content),
            timestamp = event.timestamp,
            isRead = false,
            isFromBot = false
        )
        repository.save(record)
    }

    /**
     * 记录一条机器人发出的消息
     */
    fun recordBotReply(conversationId: String, content: String) {
        val record = ChatMessageRecord(
            conversationId = conversationId,
            senderId = 0L,
            senderName = "bot",
            content = content,
            messageType = detectMessageType(content),
            timestamp = System.currentTimeMillis(),
            isRead = true,
            isFromBot = true
        )
        repository.save(record)
    }

    /**
     * 记录一条历史/爬楼拉到的消息（已读）
     *
     * 用稳定 id 做去重，避免反复爬楼写入重复记录。
     */
    fun recordHistoryMessage(
        conversationId: String,
        senderId: Long,
        senderName: String,
        content: String,
        timestamp: Long,
        isFromBot: Boolean
    ) {
        val record = ChatMessageRecord(
            id = "hist:$conversationId:$senderId:$timestamp:${content.hashCode()}",
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            content = content,
            messageType = detectMessageType(content),
            timestamp = timestamp,
            isRead = true,
            isFromBot = isFromBot
        )
        repository.save(record)
    }

    /**
     * 获取某个会话的聊天历史
     *
     * 读取时按「物理消息」去重：同一发送者、10s 内、归一化内容相同、且至少一条来自
     * 爬楼（hist: id）的记录视为同一消息——实时入档与爬楼入档各写一条，
     * 不去重会让 LLM 看到人人复读的假象。两条都非 hist 的同内容记录
     * （疑似真实连发）保留。
     */
    fun getHistory(conversationId: String, limit: Int = 20): List<ChatMessageRecord> {
        // limit=0 表示全量（JsonFileChatHistoryRepository 约定），先去重再截断
        val all = repository.findByConversation(conversationId, 0)
        val deduped = dedupeByPhysicalMessage(all)
        return if (limit > 0) deduped.takeLast(limit) else deduped
    }

    /** CQ 码段（[CQ:at,qq=1] 等），比较内容前剥掉 */
    private val cqCodeRegex = Regex("""\[CQ:[^\]]*]""")

    /** describe 富占位符（[图片]/[表情:14]/@昵称(QQ)/回复 #id：等），比较内容前剥掉 */
    private val richPlaceholderRegex = Regex(
        """\[图片[^\]]*]|\[表情:[^\]]*]|\[表情[^\]]*]|@\S+\(\d+\)|@\d+|回复\s*#\d+：|回复\s*@\S+\(\d+\)："""
    )

    /** 同一物理消息的时间容差（实时入档用本地时钟，爬楼用服务端秒级时间戳） */
    private val duplicateWindowMs = 10_000L

    private fun normalize(content: String): String =
        content.replace(cqCodeRegex, "").replace(richPlaceholderRegex, "").trim()

    /** bot 记录 senderId 不一致（实时 0 / 爬楼 selfId），用 isFromBot 区分即可 */
    private fun sameSender(a: ChatMessageRecord, b: ChatMessageRecord): Boolean =
        a.isFromBot == b.isFromBot && (a.isFromBot || a.senderId == b.senderId)

    private fun dedupeByPhysicalMessage(records: List<ChatMessageRecord>): List<ChatMessageRecord> {
        val kept = mutableListOf<ChatMessageRecord>()
        for (record in records.sortedBy { it.timestamp }) {
            val dupIndex = kept.indexOfFirst { existing ->
                sameSender(existing, record) &&
                    kotlin.math.abs(existing.timestamp - record.timestamp) <= duplicateWindowMs &&
                    normalize(existing.content) == normalize(record.content) &&
                    (record.id.startsWith("hist:") || existing.id.startsWith("hist:"))
            }
            if (dupIndex < 0) {
                kept += record
            } else {
                // 保留更干净的版本（不含 CQ 码的对 prompt 友好），位置不变
                val existing = kept[dupIndex]
                if (existing.content.contains("[CQ:") && !record.content.contains("[CQ:")) {
                    kept[dupIndex] = record
                }
            }
        }
        return kept
    }

    /**
     * 获取聊天历史摘要（注入 LLM prompt）
     */
    fun getHistorySummary(conversationId: String, limit: Int = 10): String {
        val records = getHistory(conversationId, limit)
        if (records.isEmpty()) return "（新会话）"

        return records.joinToString("\n") { record ->
            val role = if (record.isFromBot) "我" else "${record.senderName}(${record.senderId})"
            "$role: ${record.content}"
        }
    }

    /**
     * 获取未读消息
     */
    fun getUnread(conversationId: String): List<ChatMessageRecord> {
        return repository.findUnread(conversationId)
    }

    /**
     * 获取所有未读会话
     */
    fun getUnreadConversations(): List<String> {
        return repository.findUnreadConversations()
    }

    /**
     * 获取未读概况（会话 → 条数）
     */
    fun getUnreadSummary(): Map<String, Int> {
        return repository.findAllUnread()
            .groupBy { it.conversationId }
            .mapValues { it.value.size }
    }

    /**
     * 标记会话已读
     */
    fun markAsRead(conversationId: String) {
        repository.markAsRead(conversationId)
    }

    /**
     * 根据发送者查消息
     */
    fun getBySender(senderId: Long): List<ChatMessageRecord> {
        return repository.findBySender(senderId)
    }

    /**
     * 持久化
     */
    fun flush() {
        repository.flush()
    }

    /**
     * 检测消息类型
     *
     * 兼容两种风格：
     * - 旧：`[CQ:at,qq=1]` / 保留 CQ
     * - 新：describe 富占位符 `[图片]` `[表情:14]` `@昵称(QQ)` `回复 #id：`
     */
    private fun detectMessageType(content: String): ChatMessageRecord.MessageType {
        val markers = listOf(
            "[图片",
            "[表情:",
            "[表情]",
            "[表情包",
            "[CQ:image",
            "[CQ:face",
            "[CQ:mface",
            "[CQ:at",
            "[CQ:reply",
            "回复 #",
            "回复 @"
        )
        val hit = markers.count { content.contains(it) } + if (content.contains("@")) 1 else 0
        return when {
            hit >= 2 -> ChatMessageRecord.MessageType.MIXED
            content.contains("[表情包") || content.contains("[CQ:mface") -> ChatMessageRecord.MessageType.MEME
            content.contains("[图片") || content.contains("[CQ:image") -> ChatMessageRecord.MessageType.IMAGE
            content.contains("[表情:") || content.contains("[表情]") || content.contains("[CQ:face") ->
                ChatMessageRecord.MessageType.FACE
            content.contains("@") || content.contains("[CQ:at") ||
                content.startsWith("回复 #") || content.startsWith("回复 @") ||
                content.contains("[CQ:reply") -> ChatMessageRecord.MessageType.AT
            else -> ChatMessageRecord.MessageType.TEXT
        }
    }
}

package org.dlucxoket.lucatbot.bot.chathistory

import org.dlucxoket.lucatbot.model.event.message.MessageEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 聊天记录服务
 *
 * **模拟真人的聊天记忆**：
 * - 记住每条消息（谁、什么时候、说了什么）
 * - 支持查历史（"上次他说了啥来着？"）
 *
 * 未读状态不在这里：由 `LucatBot.unreadQueue` 统一维护（进会话即消费）。
 *
 * **OOP 设计**：
 * - 依赖 [ChatHistoryRepository] 接口（DIP）
 * - 单一职责：聊天记录的存取与查询
 */
@Component
class ChatHistoryService(
    private val repository: ChatHistoryRepository
) {
    private val log = LoggerFactory.getLogger(ChatHistoryService::class.java)

    /**
     * 记录一条收到的消息
     *
     * 带 message_id 时用稳定 id（`msg:<会话>:<id>`）——同一消息重投递
     * 直接覆盖，也与发送回执/折叠后的引用解析共享同一标识。
     */
    fun recordMessage(event: MessageEvent, content: String) {
        val record = ChatMessageRecord(
            id = event.messageId?.let { "msg:${event.conversationId}:$it" }
                ?: java.util.UUID.randomUUID().toString(),
            conversationId = event.conversationId,
            senderId = event.sender.qqNumber,
            // 展示名统一走 displayName（群聊群名片、私聊 QQ 昵称）；
            // QQ 昵称另存，供 prompt 注解「名片（QQ昵称 xx）」
            senderName = event.sender.displayName,
            senderNickname = event.sender.nickname,
            content = content,
            messageType = detectMessageType(content),
            timestamp = event.timestamp,
            messageId = event.messageId,
            isFromBot = false
        )
        repository.save(record)
    }

    /**
     * 记录一条机器人发出的消息
     *
     * @param messageId 发送回执里的 message_id（可空）——有则用稳定 id，
     *                  网友回复这条消息时引用解析才能定位到它
     */
    fun recordBotReply(conversationId: String, content: String, messageId: Long? = null) {
        val record = ChatMessageRecord(
            id = messageId?.let { "msg:$conversationId:$it" }
                ?: java.util.UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = 0L,
            senderName = "bot",
            content = content,
            messageType = detectMessageType(content),
            timestamp = System.currentTimeMillis(),
            messageId = messageId,
            isFromBot = true
        )
        repository.save(record)
    }

    /**
     * 记录一条历史/爬楼拉到的消息（已读）
     *
     * 带 message_id 时与实时入档同用稳定 id（`msg:<会话>:<id>`）——同一物理消息
     * 靠同一主键 upsert 天然合一，不再产生孪生双份；无 id（如 Shiro 类型化
     * 回退路径）才落 `hist:` 兜底 id，由读取 fold 与启动清理收敛。
     *
     * @param senderName 展示名（群聊群名片优先，与实时入档同一规则）
     * @param senderNickname QQ 昵称（与展示名不同时供 prompt 注解）
     * @param messageId 爬楼响应里的 message_id（可空）
     */
    fun recordHistoryMessage(
        conversationId: String,
        senderId: Long,
        senderName: String,
        content: String,
        timestamp: Long,
        isFromBot: Boolean,
        senderNickname: String? = null,
        messageId: Long? = null
    ) {
        val record = ChatMessageRecord(
            id = messageId?.let { "msg:$conversationId:$it" }
                ?: "hist:$conversationId:$senderId:$timestamp:${content.hashCode()}",
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            senderNickname = senderNickname,
            content = content,
            messageType = detectMessageType(content),
            timestamp = timestamp,
            messageId = messageId,
            isFromBot = isFromBot
        )
        repository.save(record)
    }

    /**
     * 获取某个会话的聊天历史
     *
     * 读取时按「物理消息」折叠孪生行（规则见 [ChatHistoryFold]）：实时入档与
     * 爬楼入档各写一条的存量数据不去重会让 LLM 看到人人复读的假象；
     * 真实连发（两个 UUID 行 / 两个不同 msg: 行）保留。
     */
    fun getHistory(conversationId: String, limit: Int = 20): List<ChatMessageRecord> {
        // limit=0 表示全量（JsonFileChatHistoryRepository 约定），先折叠再截断
        val all = repository.findByConversation(conversationId, 0)
        val deduped = ChatHistoryFold.fold(all).kept
        return if (limit > 0) deduped.takeLast(limit) else deduped
    }

    /**
     * 启动清理：把存储层的孪生双份行合并为一条（幂等，每次启动跑一遍）
     *
     * 存量双份（实时/回执行 × 爬楼 `hist:` 行）此前只在读取时折叠、存储层
     * 一直双倍膨胀——这里用同一套 [ChatHistoryFold] 规则落到存储：
     * - 内容取干净者、id 取权威者（`msg:` > UUID > `hist:`）、messageId 补齐 → save
     * - 被并掉的原始 id → delete
     *
     * 与 json 启动同步（INSERT IGNORE 按 id）配合：迁移源同步折叠后不再回灌。
     */
    fun collapseStoredTwins() {
        val all = repository.findAll()
        if (all.isEmpty()) return
        var updated = 0
        var removed = 0
        for (rows in all.groupBy { it.conversationId }.values) {
            if (rows.size < 2) continue
            val originalById = rows.associateBy { it.id }
            val folded = ChatHistoryFold.fold(rows)
            for (merged in folded.kept) {
                val original = originalById[merged.id]
                if (original != null && original != merged) {
                    repository.save(merged)
                    updated++
                }
            }
            val keptIds = folded.kept.mapTo(mutableSetOf()) { it.id }
            for (id in originalById.keys.filterNot { it in keptIds }) {
                repository.delete(id)
                removed++
            }
        }
        if (updated > 0 || removed > 0) {
            repository.flush()
            log.info("启动清理：合并孪生双份 {} 行、更新 {} 行", removed, updated)
        }
    }

    /**
     * 获取聊天历史摘要（注入 LLM prompt）
     */
    fun getHistorySummary(conversationId: String, limit: Int = 10): String {
        val records = getHistory(conversationId, limit)
        if (records.isEmpty()) return "（新会话）"

        return records.joinToString("\n") { record ->
            // 展示名 + 名片/QQ昵称注解（同号不同名时点破是同一人）
            val role = if (record.isFromBot) {
                "我"
            } else {
                "${formatSenderDisplay(record.senderName, record.senderNickname)}(${record.senderId})"
            }
            "$role: ${record.content}"
        }
    }

    /**
     * 会话里「对方」（指定 QQ 号、非机器人）最近一条消息的展示名
     *
     * 群名片/QQ昵称注解一并带上（同号不同名时点破是同一人）。
     * 查无（新会话/对方还没说过话）→ null，由调用方回退。
     */
    fun peerDisplayName(conversationId: String, peerId: Long, limit: Int = 100): String? =
        getHistory(conversationId, limit)
            .lastOrNull { !it.isFromBot && it.senderId == peerId }
            ?.let { formatSenderDisplay(it.senderName, it.senderNickname) }

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

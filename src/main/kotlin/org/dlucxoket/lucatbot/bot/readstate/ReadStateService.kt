package org.dlucxoket.lucatbot.bot.readstate

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.ReadStateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 已读状态服务——会话级水位的写入与**重启后未读重建**
 *
 * 设计：已读不再只存内存（原 LucatBot.unreadQueue 重启即丢），改为
 * **会话级水位**（该会话最后已读消息的时间）持久化：
 * - **写水位**：进会话即 `markRead` 推到该会话最后一条消息时间
 *   （进会话=看到全部历史）；无记录的消息自然成为「未读」
 * - **重建**：重启后（selfId 已知时）把水位之后的聊天记录灌回未读队列——
 *   `n条未读`/`@我`/`@全体` 标记随之恢复；每会话最多重建
 *   [maxPerConversation] 条（防爆）
 *
 * isAtMe 解析按 describe 文本形态（`@昵称(QQ)` / `@QQ`），故重建必须在
 * bot 连接、selfId 已知之后触发（MainPlugin 首条消息处，幂等一次）。
 */
@Component
class ReadStateService(
    private val chatHistoryService: ChatHistoryService,
    private val readStateRepository: ReadStateRepository
) {
    private val log = LoggerFactory.getLogger(ReadStateService::class.java)

    /** 重建幂等标志（只在进程内做一次） */
    @Volatile
    private var rebuilt = false

    /**
     * 进会话即标已读：水位推到该会话最后一条消息的时间（无记录则不动）
     *
     * 失败只 log——水位写不进绝不影响进会话主流程。
     */
    fun markRead(conversationId: String) {
        try {
            val maxTs = chatHistoryService.getHistory(conversationId, 1)
                .lastOrNull()?.timestamp ?: return
            readStateRepository.markRead(conversationId, maxTs)
        } catch (e: Exception) {
            log.warn("写已读水位失败（忽略）{}: {}", conversationId, e.message)
        }
    }

    /**
     * 重启后按水位重建未读（幂等；[MainPlugin] 首条消息 selfId 已知后调用）
     *
     * 水位之后的消息 = 重启前没看的 → 灌回 LucatBot 未读队列（仅当队列为空，
     * 不覆盖运行中实时积累的未读）。
     */
    fun rebuildUnread(lucatBot: LucatBot, selfId: Long) {
        if (rebuilt) return
        rebuilt = true
        try {
            val watermarks = readStateRepository.allLastRead()
            if (watermarks.isEmpty()) return
            val restored = mutableListOf<LucatBot.UnreadMessage>()
            for ((convId, water) in watermarks) {
                chatHistoryService.findSince(convId, water)
                    .takeLast(maxPerConversation)
                    .forEach { r ->
                        restored += LucatBot.UnreadMessage(
                            conversationId = convId,
                            conversationType = if (convId.startsWith("group:")) {
                                LucatBot.ConversationType.GROUP
                            } else {
                                LucatBot.ConversationType.PRIVATE
                            },
                            senderId = r.senderId,
                            senderName = r.senderName,
                            content = r.content,
                            isAtMe = mentionsSelf(r.content, selfId),
                            isAtAll = r.content.contains("@全体成员"),
                            timestamp = r.timestamp,
                            messageId = r.messageId,
                            imageUrls = r.imageUrls
                        )
                    }
            }
            if (restored.isNotEmpty()) {
                lucatBot.restoreUnread(restored)
            }
            log.info("按已读水位重建未读 {} 条（覆盖 {} 个会话的水位）",
                restored.size, watermarks.size)
        } catch (e: Exception) {
            log.warn("重建未读失败（忽略）: {}", e.message)
        }
    }

    /**
     * 内容里是否 @了自己——按 describe 文本形态：
     * `@昵称(3046673325)` / `@3046673325`（后跟非数字防前缀误配）
     */
    private fun mentionsSelf(content: String, selfId: Long): Boolean =
        Regex("""@[^()\s]*\($selfId\)|@$selfId(?!\d)""").containsMatchIn(content)

    private companion object {
        /** 每会话重建上限（防极端积压爆队列） */
        const val maxPerConversation = 100
    }
}

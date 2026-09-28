package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.bot.tool.data.HistoryMessageItem

/**
 * 聊天记录查询工具
 *
 * 优先从本地存储读取；Bot 已绑定时会尝试向 OneBot 拉取历史（爬楼），
 * **拉到的消息一律写入 [ChatHistoryService] 持久化**，再返回合并后的列表。
 *
 * JSON 边界：`{"targetId": 123, "count": 10, "type": "private"}` ↔ `ChatHistoryRequest`
 */
object ChatHistoryTool : Tool<ChatHistoryRequest, ChatHistoryResult>() {
    override val name: String = "ChatHistoryTool"

    override val description: String = "查询聊天记录。返回指定会话的最近消息列表。"

    override val parameterHint: String = "JSON: {\"targetId\": 123456, \"count\": 10, \"type\": \"group\"或\"private\"}"

    override val inputClass: Class<ChatHistoryRequest> = ChatHistoryRequest::class.java

    /** 聊天记录服务引用（启动时经 [bindServices] 注入，不对外暴露） */
    @Volatile
    private var chatHistoryService: ChatHistoryService? = null

    override fun bindServices(services: ToolServices) {
        chatHistoryService = services.chatHistoryService
    }

    override fun handle(input: ChatHistoryRequest): ChatHistoryResult {
        val service = chatHistoryService ?: return ChatHistoryResult(
            targetId = input.targetId, success = false, error = "ChatHistoryService 未绑定"
        )

        return try {
            val conversationId = "${input.type}:${input.targetId}"

            // 爬楼：能拉远端就拉，拉到就落盘
            fetchRemoteAndPersist(service, conversationId, input)

            val records = service.getHistory(conversationId, input.count)

            ChatHistoryResult(
                targetId = input.targetId,
                messages = records.map {
                    HistoryMessageItem(
                        sender = it.senderName,
                        content = it.content,
                        timestamp = it.timestamp
                    )
                },
                success = true
            )
        } catch (e: Exception) {
            ChatHistoryResult(targetId = input.targetId, success = false, error = e.message)
        }
    }

    /**
     * 从 OneBot 拉取历史消息并写入本地聊天记录（去重靠稳定 id）
     */
    private fun fetchRemoteAndPersist(
        service: ChatHistoryService,
        conversationId: String,
        input: ChatHistoryRequest
    ) {
        val b = bot ?: return
        val isGroup = input.type == "group"
        val targetId = input.targetId

        try {
            val actionData = if (isGroup) {
                b.getGroupMsgHistory(targetId, null, input.count, true)
            } else {
                b.getFriendMsgHistory(targetId, null, input.count, true)
            }
            val messages = actionData?.data?.messages ?: return
            val selfId = b.selfId

            messages.forEach { msg ->
                val content = msg.message ?: return@forEach
                if (content.isBlank()) return@forEach

                val senderId = msg.sender?.userId?.toLongOrNull()
                    ?: msg.userId
                    ?: 0L
                val senderName = msg.sender?.card?.takeIf { it.isNotBlank() }
                    ?: msg.sender?.nickname?.takeIf { it.isNotBlank() }
                    ?: if (senderId == selfId) "bot" else senderId.toString()

                val rawTime = msg.time ?: 0L
                val timestamp = when {
                    rawTime <= 0L -> System.currentTimeMillis()
                    rawTime < 1_000_000_000_000L -> rawTime * 1000L  // 秒级时间戳
                    else -> rawTime
                }

                service.recordHistoryMessage(
                    conversationId = conversationId,
                    senderId = senderId,
                    senderName = senderName,
                    content = content,
                    timestamp = timestamp,
                    isFromBot = senderId == selfId
                )
            }
        } catch (e: Exception) {
            // 远端失败不影响本地读取
            org.slf4j.LoggerFactory.getLogger(ChatHistoryTool::class.java)
                .warn("爬楼拉取远端历史失败: {}", e.message)
        }
    }
}

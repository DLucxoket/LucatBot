package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.bot.tool.data.HistoryMessageItem

/**
 * 聊天记录查询工具（本地存储版）
 *
 * 从本地聊天记录存储查询，不依赖 OneBot API。
 * JSON 边界：`{"targetId": 123, "count": 10, "type": "private"}` ↔ `ChatHistoryRequest`
 */
object ChatHistoryTool : Tool<ChatHistoryRequest, ChatHistoryResult>() {
    override val name: String = "ChatHistoryTool"

    override val description: String = "查询聊天记录。返回指定会话的最近消息列表。"

    override val parameterHint: String = "JSON: {\"targetId\": 123456, \"count\": 10, \"type\": \"group\"或\"private\"}"

    override val inputClass: Class<ChatHistoryRequest> = ChatHistoryRequest::class.java

    /** 聊天记录服务引用 */
    @Volatile var chatHistoryService: ChatHistoryService? = null

    override fun handle(input: ChatHistoryRequest): ChatHistoryResult {
        val service = chatHistoryService ?: return ChatHistoryResult(
            targetId = input.targetId, success = false, error = "ChatHistoryService 未绑定"
        )

        return try {
            val conversationId = "${input.type}:${input.targetId}"
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
}

package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult

/**
 * 聊天记录查询工具
 *
 * 查询指定群或私聊的最近聊天记录。
 * JSON 边界：`{"targetId": 123, "count": 10}` ↔ `ChatHistoryRequest`
 * OOP 内部：`ChatHistoryRequest` → `ChatHistoryResult`
 */
object ChatHistoryTool : Tool<ChatHistoryRequest, ChatHistoryResult>() {
    override val name: String = "ChatHistoryTool"

    override val description: String = "查询聊天记录。返回指定会话的最近消息列表。"

    override val parameterHint: String = "JSON: {\"targetId\": 123456, \"count\": 10}"

    override val inputClass: Class<ChatHistoryRequest> = ChatHistoryRequest::class.java

    override fun handle(input: ChatHistoryRequest): ChatHistoryResult {
        val b = bot ?: return ChatHistoryResult(
            targetId = input.targetId, success = false, error = "Bot 未绑定"
        )

        // TODO: 调用 OneBot API 查询聊天记录
        return ChatHistoryResult(
            targetId = input.targetId,
            messages = emptyList(),
            success = true
        )
    }
}

package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.MessageListRequest
import org.dlucxoket.lucatbot.bot.tool.data.MessageListResult

/**
 * 消息列表查询工具
 *
 * 查看指定会话的消息概览（条数、最后一条等）。
 * JSON 边界：`{"targetId": 123}` ↔ `MessageListRequest`
 * OOP 内部：`MessageListRequest` → `MessageListResult`
 */
object MessageListTool : Tool<MessageListRequest, MessageListResult>() {
    override val name: String = "MessageListTool"

    override val description: String = "功能：向 OneBot 拉取指定会话最近 20 条消息（不落库），返回消息条数和最后一条内容，用于快速概览。\n何时使用：消息列表界面由 MessageListController 直读未读队列生成、不经过本工具；仅当想快速看某会话有多少条消息、最后说了什么，而不想读完整聊天记录时调用。"

    override val parameterHint: String = "JSON: {\"targetId\": 123456, \"type\": \"group\"或\"private\"}"

    override val inputClass: Class<MessageListRequest> = MessageListRequest::class.java

    override fun handle(input: MessageListRequest): MessageListResult {
        val b = bot ?: return MessageListResult(
            targetId = input.targetId, success = false, error = "Bot 未绑定"
        )

        return try {
            val actionData = if (input.type == "group") {
                b.getGroupMsgHistory(input.targetId, null, 20, true)
            } else {
                b.getFriendMsgHistory(input.targetId, null, 20, true)
            }

            val messages = actionData?.data?.messages ?: emptyList()
            val lastMessage = messages.lastOrNull()

            MessageListResult(
                targetId = input.targetId,
                messageCount = messages.size,
                lastMessage = lastMessage?.message ?: "",
                success = true
            )
        } catch (e: Exception) {
            MessageListResult(
                targetId = input.targetId, success = false, error = e.message
            )
        }
    }
}

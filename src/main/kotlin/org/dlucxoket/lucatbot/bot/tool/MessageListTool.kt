package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.MessageListRequest
import org.dlucxoket.lucatbot.bot.tool.data.MessageListResult

/**
 * 消息列表查询工具
 *
 * 查看指定会话的消息列表摘要。
 * JSON 边界：`{"targetId": 123}` ↔ `MessageListRequest`
 * OOP 内部：`MessageListRequest` → `MessageListResult`
 */
object MessageListTool : Tool<MessageListRequest, MessageListResult>() {
    override val name: String = "MessageListTool"

    override val description: String = "查看消息列表。获取指定会话的消息概览。"

    override val parameterHint: String = "JSON: {\"targetId\": 123456}"

    override val inputClass: Class<MessageListRequest> = MessageListRequest::class.java

    override fun handle(input: MessageListRequest): MessageListResult {
        val b = bot ?: return MessageListResult(
            targetId = input.targetId, success = false, error = "Bot 未绑定"
        )

        // TODO: 调用 OneBot API 获取消息列表
        return MessageListResult(targetId = input.targetId, success = true)
    }
}

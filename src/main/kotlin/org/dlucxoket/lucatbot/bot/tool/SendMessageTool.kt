package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService

/**
 * 发送消息工具
 *
 * 模拟真人在会话里打字发送消息的动作。
 * 发送成功后**必须写入聊天记录**（isBot=true），避免 LLM 看到失忆上下文。
 */
object SendMessageTool : Tool<SendMessageRequest, SendMessageResult>() {
    override val name: String = "SendMessageTool"

    override val description: String = "向指定会话发送文本消息。"

    override val parameterHint: String = "JSON: {\"conversationId\": \"private:123456\", \"content\": \"你好\"}"

    override val inputClass: Class<SendMessageRequest> = SendMessageRequest::class.java

    /** 聊天记录服务（发送成功后写入自身发言） */
    @Volatile var chatHistoryService: ChatHistoryService? = null

    override fun handle(input: SendMessageRequest): SendMessageResult {
        val b = bot ?: return SendMessageResult(success = false, error = "Bot 未绑定")

        return try {
            val parts = input.conversationId.split(":")
            if (parts.size != 2) {
                return SendMessageResult(success = false, error = "会话ID格式错误: ${input.conversationId}")
            }
            val type = parts[0]
            val id = parts[1].toLongOrNull() ?: return SendMessageResult(success = false, error = "无效ID")

            when (type) {
                "group" -> b.sendGroupMsg(id, input.content, false)
                "private" -> b.sendPrivateMsg(id, input.content, false)
                else -> return SendMessageResult(success = false, error = "未知会话类型: $type")
            }

            // 发出后必须写入聊天记录（isBot=true）
            chatHistoryService?.recordBotReply(input.conversationId, input.content)

            SendMessageResult(success = true)
        } catch (e: Exception) {
            SendMessageResult(success = false, error = e.message)
        }
    }
}

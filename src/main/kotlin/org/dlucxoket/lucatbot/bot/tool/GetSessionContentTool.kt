package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.LucatBot

/**
 * 获取会话内容工具
 *
 * 模拟真人点进会话看聊天记录的动作：
 * - 拉取该会话的未读消息
 * - 标记已读
 */
object GetSessionContentTool : Tool<GetSessionContentRequest, GetSessionContentResult>() {
    override val name: String = "GetSessionContentTool"

    override val description: String = "查看指定会话的聊天内容。进入会话后可以看到完整消息并标记已读。"

    override val parameterHint: String = "JSON: {\"conversationId\": \"private:123456\", \"limit\": 10}"

    override val inputClass: Class<GetSessionContentRequest> = GetSessionContentRequest::class.java

    /** 机器人状态机引用（启动时经 [bindServices] 注入，不对外暴露） */
    @Volatile
    private var lucatBot: LucatBot? = null

    override fun bindServices(services: ToolServices) {
        lucatBot = services.lucatBot
    }

    override fun handle(input: GetSessionContentRequest): GetSessionContentResult {
        val bot = lucatBot ?: return GetSessionContentResult(success = false, error = "Bot 未绑定")

        // 从 LucatBot 获取并消费未读消息
        val messages = bot.consumeUnread(input.conversationId)
            .takeLast(input.limit)
            .map {
                SessionMessage(
                    sender = it.senderName,
                    content = it.content,
                    timestamp = it.timestamp
                )
            }

        return GetSessionContentResult(messages = messages, success = true)
    }
}

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

    override val description: String = "功能：读取并消费指定会话的未读队列，返回最后 limit 条未读消息（发送者、内容、时间）；副作用是这些未读被清除，等于标记已读。\n何时使用：主流程的消息列表由 MessageListController 直读未读队列生成、进入会话由编排层自动拉聊天记录，均不经过本工具；仅当想主动点开某个会话、领取其未读并标记已读时才调用，注意调用后这些未读不再保留在队列里。"

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

        return tryResult(onError = { GetSessionContentResult(success = false, error = it) }) {
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
            GetSessionContentResult(messages = messages, success = true)
        }
    }
}

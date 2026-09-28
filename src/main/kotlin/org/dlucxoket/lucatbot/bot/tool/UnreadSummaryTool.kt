package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.LucatBot

/**
 * 未读消息概况工具
 *
 * 模拟真人扫一眼消息列表的动作：
 * - 只看「哪个会话有消息、多少条」
 * - 不看完整内容
 * - 保留未读状态
 *
 * JSON 边界：`{}` ↔ `UnreadSummaryRequest`
 * OOP 内部：返回各会话的未读概况
 */
object UnreadSummaryTool : Tool<UnreadSummaryRequest, UnreadSummaryResult>() {
    override val name: String = "UnreadSummaryTool"

    override val description: String = """
        查看未读消息概况。返回哪些会话有未读消息和条数，不看内容。
        用于快速了解谁找你了，模拟真人扫一眼消息列表。
    """.trimIndent()

    override val parameterHint: String = "JSON: {}"

    override val inputClass: Class<UnreadSummaryRequest> = UnreadSummaryRequest::class.java

    /** 机器人状态机引用（用于读取未读队列；启动时经 [bindServices] 注入） */
    @Volatile
    private var lucatBot: LucatBot? = null

    override fun bindServices(services: ToolServices) {
        lucatBot = services.lucatBot
    }

    override fun handle(input: UnreadSummaryRequest): UnreadSummaryResult {
        val bot = lucatBot ?: return UnreadSummaryResult(success = false, error = "Bot 未绑定")
        val summary = bot.getUnreadSummary()
        return UnreadSummaryResult(
            conversations = summary.map { (convId, count) ->
                UnreadConversation(
                    conversationId = convId,
                    count = count,
                    isAtMe = bot.hasUnreadAtMe(convId),
                    isAtAll = bot.hasUnreadAtAll(convId)
                )
            },
            success = true
        )
    }
}

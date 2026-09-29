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

    override val description: String = "功能：汇总当前未读队列，返回每个有未读的会话 ID、未读条数、是否含 @我 和 @全体；只读不消费未读，也不返回消息内容。\n何时使用：消息列表由 MessageListController 直接汇总未读生成，主流程不调用本工具；仅当想快速扫一眼谁找你、各有几条、有没有 @我 的概览而不想看内容时调用。"

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
        return try {
            val summary = bot.getUnreadSummary()
            UnreadSummaryResult(
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
        } catch (e: Exception) {
            UnreadSummaryResult(success = false, error = e.message)
        }
    }
}

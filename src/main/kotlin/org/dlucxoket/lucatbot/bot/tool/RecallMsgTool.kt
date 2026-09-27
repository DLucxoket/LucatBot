package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.RecallMsgRequest
import org.dlucxoket.lucatbot.bot.tool.data.RecallMsgResult

/**
 * 撤回消息工具
 *
 * 撤回指定消息（模拟真人手滑发错消息会撤回）。
 */
object RecallMsgTool : Tool<RecallMsgRequest, RecallMsgResult>() {
    override val name: String = "RecallMsgTool"
    override val description: String = "撤回一条消息。模拟真人发错消息后撤回。"
    override val parameterHint: String = "JSON: {\"messageId\": 123}"
    override val inputClass: Class<RecallMsgRequest> = RecallMsgRequest::class.java

    override fun handle(input: RecallMsgRequest): RecallMsgResult {
        val b = bot ?: return RecallMsgResult(success = false, error = "Bot 未绑定")

        return try {
            b.deleteMsg(input.messageId)
            RecallMsgResult(success = true)
        } catch (e: Exception) {
            RecallMsgResult(success = false, error = e.message)
        }
    }
}

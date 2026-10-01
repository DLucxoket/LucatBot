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
    override val description: String = "功能：按消息 ID 撤回一条消息（通常是机器人自己刚发出的消息），返回是否成功。\n何时使用：破坏性操作，撤回后消息对所有人消失且受平台撤回时限限制；仅在发错消息需要撤回这类明确场景谨慎调用，不要随意撤回。"
    override val parameterHint: String = "JSON: {\"messageId\": 123}"
    override val inputClass: Class<RecallMsgRequest> = RecallMsgRequest::class.java

    override fun handle(input: RecallMsgRequest): RecallMsgResult = runTool(
        onError = { RecallMsgResult(success = false, error = it) }
    ) { b ->
        b.deleteMsg(input.messageId)
        RecallMsgResult(success = true)
    }
}

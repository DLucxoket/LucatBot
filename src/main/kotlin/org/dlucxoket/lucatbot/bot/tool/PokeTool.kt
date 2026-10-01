package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.PokeRequest
import org.dlucxoket.lucatbot.bot.tool.data.PokeResult

/**
 * 戳一戳工具
 *
 * 戳某人一下（模拟真人戳朋友）。
 */
object PokeTool : Tool<PokeRequest, PokeResult>() {
    override val name: String = "PokeTool"
    override val description: String = "功能：向指定会话发送戳一戳（群聊戳群成员、私聊戳好友），返回是否成功。\n何时使用：想模拟真人主动戳人打招呼、催回复或回应别人的戳时调用；戳一戳旁路事件由编排层直接处理，本工具用于主动发起戳一戳的补充场景。"
    override val parameterHint: String = "JSON: {\"conversationId\": \"private:123\", \"userId\": 456}"
    override val inputClass: Class<PokeRequest> = PokeRequest::class.java

    override fun handle(input: PokeRequest): PokeResult = runTool(
        onError = { PokeResult(success = false, error = it) }
    ) { b ->
        val parts = input.conversationId.split(":")
        if (parts.size != 2) return@runTool PokeResult(success = false, error = "会话ID格式错误")

        when (parts[0]) {
            "group" -> {
                val groupId = parts[1].toLongOrNull()
                    ?: return@runTool PokeResult(success = false, error = "无效群号: ${parts[1]}")
                b.sendGroupPoke(groupId, input.userId)
            }
            "private" -> b.sendFriendPoke(input.userId)
            else -> return@runTool PokeResult(success = false, error = "未知会话类型")
        }
        PokeResult(success = true)
    }
}

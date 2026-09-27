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
    override val description: String = "戳一戳某人。模拟真人戳朋友的动作。"
    override val parameterHint: String = "JSON: {\"conversationId\": \"private:123\", \"userId\": 456}"
    override val inputClass: Class<PokeRequest> = PokeRequest::class.java

    override fun handle(input: PokeRequest): PokeResult {
        val b = bot ?: return PokeResult(success = false, error = "Bot 未绑定")

        return try {
            val parts = input.conversationId.split(":")
            if (parts.size != 2) return PokeResult(success = false, error = "会话ID格式错误")

            when (parts[0]) {
                "group" -> b.sendGroupPoke(parts[1].toLongOrNull() ?: 0, input.userId)
                "private" -> b.sendFriendPoke(input.userId)
                else -> return PokeResult(success = false, error = "未知会话类型")
            }
            PokeResult(success = true)
        } catch (e: Exception) {
            PokeResult(success = false, error = e.message)
        }
    }
}

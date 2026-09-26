package org.dlucxoket.lucatbot.bot.tool

import com.mikuac.shiro.common.utils.MsgUtils

/**
 * 发送表情工具
 *
 * 模拟真人发表情/表情包的动作。
 */
object SendEmojiTool : Tool<SendEmojiRequest, SendEmojiResult>() {
    override val name: String = "SendEmojiTool"

    override val description: String = "向指定会话发送表情/表情包。"

    override val parameterHint: String = "JSON: {\"conversationId\": \"private:123456\", \"emojiName\": \"笑哭\"}"

    override val inputClass: Class<SendEmojiRequest> = SendEmojiRequest::class.java

    override fun handle(input: SendEmojiRequest): SendEmojiResult {
        val b = bot ?: return SendEmojiResult(success = false, error = "Bot 未绑定")

        return try {
            val parts = input.conversationId.split(":")
            if (parts.size != 2) {
                return SendEmojiResult(success = false, error = "会话ID格式错误")
            }
            val type = parts[0]
            val id = parts[1].toLongOrNull() ?: return SendEmojiResult(success = false, error = "无效ID")

            // 使用 MsgUtils 构建表情消息
            val msg = MsgUtils.builder().face(66).build() // 默认笑脸

            when (type) {
                "group" -> b.sendGroupMsg(id, msg, false)
                "private" -> b.sendPrivateMsg(id, msg, false)
                else -> return SendEmojiResult(success = false, error = "未知会话类型")
            }

            SendEmojiResult(success = true)
        } catch (e: Exception) {
            SendEmojiResult(success = false, error = e.message)
        }
    }
}

package org.dlucxoket.lucatbot.bot.tool

import com.mikuac.shiro.common.utils.MsgUtils

/**
 * 发送表情工具
 *
 * 模拟真人发表情/表情包的动作。
 */
object SendEmojiTool : Tool<SendEmojiRequest, SendEmojiResult>() {
    override val name: String = "SendEmojiTool"

    override val description: String = "功能：向指定会话发送一个 QQ 小表情，emojiName 传数字表情 ID（非数字则回退为默认笑脸 66），返回是否成功；成功后不自动写入聊天记录。\n何时使用：想用表情代替文字回应、或给文字补充情绪时调用；注意 send 动作只发文本，发表情需要单独调用本工具。"

    override val parameterHint: String = "JSON: {\"conversationId\": \"private:123456\", \"emojiName\": \"66\"}"

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

            val faceId = resolveFaceId(input.emojiName)
            val msg = MsgUtils.builder().face(faceId).build()

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

    /**
     * 把 emojiName 解析为 QQ 表情 ID。
     * 支持直接传数字 ID；其他情况退回默认笑脸 66。
     */
    private fun resolveFaceId(emojiName: String): Int {
        return emojiName.trim().toIntOrNull() ?: 66
    }
}

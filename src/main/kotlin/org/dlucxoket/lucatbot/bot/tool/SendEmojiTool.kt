package org.dlucxoket.lucatbot.bot.tool

/**
 * 发送表情工具
 *
 * 模拟真人发表情/表情包的动作。
 */
object SendEmojiTool : Tool<SendEmojiRequest, SendEmojiResult>() {
    override val name: String = "SendEmojiTool"

    override val description: String = "功能：向指定会话单独发送一个 QQ 小表情（不带文字），emojiName 传数字表情 ID（非数字则回退为默认笑脸 66），返回是否成功；成功后不自动写入聊天记录。\n何时使用：只想发个表情不配文字时调用；带文字的表情直接在 send 的 content 里写 [表情:数字] 即可、无需本工具。表情只在确有必要时用（回应对方的表情、情绪到了），不要每条消息都带。"

    override val parameterHint: String = "JSON: {\"conversationId\": \"private:123456\", \"emojiName\": \"66\"}"

    override val inputClass: Class<SendEmojiRequest> = SendEmojiRequest::class.java

    override fun handle(input: SendEmojiRequest): SendEmojiResult = runTool(
        onError = { SendEmojiResult(success = false, error = it) }
    ) { b ->
        val parts = input.conversationId.split(":")
        if (parts.size != 2) {
            return@runTool SendEmojiResult(success = false, error = "会话ID格式错误")
        }
        val type = parts[0]
        val id = parts[1].toLongOrNull()
            ?: return@runTool SendEmojiResult(success = false, error = "无效ID")

        // OneBot 标准 CQ 码表情段——core 层直连字符串，不引 Shiro 工具类
        val msg = "[CQ:face,id=${resolveFaceId(input.emojiName)}]"

        when (type) {
            "group" -> b.sendGroupMsg(id, msg, false)
            "private" -> b.sendPrivateMsg(id, msg, false)
            else -> return@runTool SendEmojiResult(success = false, error = "未知会话类型")
        }

        SendEmojiResult(success = true)
    }

    /**
     * 把 emojiName 解析为 QQ 表情 ID。
     * 支持直接传数字 ID；其他情况退回默认笑脸 66。
     */
    private fun resolveFaceId(emojiName: String): Int {
        return emojiName.trim().toIntOrNull() ?: 66
    }
}

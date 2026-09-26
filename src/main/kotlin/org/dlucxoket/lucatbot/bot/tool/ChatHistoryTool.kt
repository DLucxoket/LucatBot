package org.dlucxoket.lucatbot.bot.tool

/**
 * 聊天记录查询工具
 *
 * 查询指定群或私聊的最近聊天记录。
 *
 * 输入格式：`群号 数量` 或 `用户QQ号 数量`
 * 输出格式：按时间排序的聊天记录摘要
 */
object ChatHistoryTool : Tool() {
    override val name: String = "ChatHistoryTool"

    override val description: String = """
        查询聊天记录。可以查群聊或私聊的最近消息。
        返回按时间排序的消息列表，每条包含发送者和内容摘要。
    """.trimIndent()

    override val parameterHint: String = "群号 数量（如：123456 10）或 用户QQ号 数量（如：123456789 10）"

    override fun execute(input: String): String {
        val parts = input.trim().split(Regex("\\s+"))
        if (parts.size < 2) {
            return "参数格式错误，应为：群号 数量 或 用户QQ号 数量"
        }

        val targetId = parts[0].toLongOrNull() ?: return "无效的ID: ${parts[0]}"
        val count = parts[1].toIntOrNull() ?: 5

        val b = bot ?: return "Bot 未绑定，无法查询"

        // TODO: 调用 Shiro Bot API 查询聊天记录
        // 目前 Shiro 的 getGroupMsgHistory 需要 messageSeq，实际实现时补充
        return "聊天记录查询：目标=$targetId，数量=$count（功能待接入 OneBot API）"
    }
}

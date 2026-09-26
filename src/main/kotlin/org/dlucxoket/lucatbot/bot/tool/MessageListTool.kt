package org.dlucxoket.lucatbot.bot.tool

/**
 * 消息列表查询工具
 *
 * 查看指定会话的消息列表摘要。
 *
 * 输入格式：`群号` 或 `用户QQ号`
 * 输出格式：消息摘要列表
 */
object MessageListTool : Tool() {
    override val name: String = "MessageListTool"

    override val description: String = """
        查看消息列表。获取指定群或私聊会话的消息摘要。
        返回最近的消息概览，适合快速了解聊天内容。
    """.trimIndent()

    override val parameterHint: String = "群号 或 用户QQ号（如：123456）"

    override fun execute(input: String): String {
        val targetId = input.trim().toLongOrNull() ?: return "无效的ID: $input"

        val b = bot ?: return "Bot 未绑定，无法查询"

        // TODO: 调用 Shiro Bot API 获取消息列表
        return "消息列表查询：目标=$targetId（功能待接入 OneBot API）"
    }
}

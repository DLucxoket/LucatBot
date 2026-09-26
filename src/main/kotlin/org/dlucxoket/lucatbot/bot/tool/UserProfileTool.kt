package org.dlucxoket.lucatbot.bot.tool

/**
 * 用户主页查询工具
 *
 * 查看指定 QQ 号的用户资料。
 *
 * 输入格式：`QQ号`
 * 输出格式：用户资料（昵称、性别、年龄等）
 */
object UserProfileTool : Tool() {
    override val name: String = "UserProfileTool"

    override val description: String = """
        查看用户主页/资料。根据QQ号获取用户的昵称、性别等基本信息。
    """.trimIndent()

    override val parameterHint: String = "QQ号（如：123456789）"

    override fun execute(input: String): String {
        val qqNumber = input.trim().toLongOrNull() ?: return "无效的QQ号: $input"

        val b = bot ?: return "Bot 未绑定，无法查询"

        // 调用 OneBot API 获取用户信息
        return try {
            val actionData = b.getStrangerInfo(qqNumber, false)
            val userInfo = actionData?.data
            if (userInfo != null) {
                """
                用户资料：
                QQ号：${userInfo.userId}
                昵称：${userInfo.nickname}
                性别：${userInfo.sex}
                年龄：${userInfo.age}
                """.trimIndent()
            } else {
                "未找到用户: $qqNumber"
            }
        } catch (e: Exception) {
            "查询用户信息失败: ${e.message}"
        }
    }
}

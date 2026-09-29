package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.UserProfileRequest
import org.dlucxoket.lucatbot.bot.tool.data.UserProfileResult

/**
 * 用户主页查询工具
 *
 * 查看指定 QQ 号的用户资料。
 * JSON 边界：`{"qqNumber": 123}` ↔ `UserProfileRequest`
 * OOP 内部：`UserProfileRequest` → `UserProfileResult`
 */
object UserProfileTool : Tool<UserProfileRequest, UserProfileResult>() {
    override val name: String = "UserProfileTool"

    override val description: String = "功能：按 QQ 号查询陌生人资料，返回昵称、性别、年龄（查不到时 found=false）。\n何时使用：只知道 QQ 号、想确认对方是谁时调用；查机器人自己的好友建议改用 FriendListTool。"

    override val parameterHint: String = "JSON: {\"qqNumber\": 123456789}"

    override val inputClass: Class<UserProfileRequest> = UserProfileRequest::class.java

    override fun handle(input: UserProfileRequest): UserProfileResult {
        val b = bot ?: return UserProfileResult(
            qqNumber = input.qqNumber, nickname = "", sex = "", age = 0,
            found = false, error = "Bot 未绑定"
        )

        return try {
            val actionData = b.getStrangerInfo(input.qqNumber, false)
            val userInfo = actionData?.data
            if (userInfo != null) {
                UserProfileResult(
                    qqNumber = userInfo.userId,
                    nickname = userInfo.nickname,
                    sex = userInfo.sex,
                    age = userInfo.age,
                    found = true
                )
            } else {
                UserProfileResult(
                    qqNumber = input.qqNumber, nickname = "", sex = "", age = 0,
                    found = false, error = "未找到用户"
                )
            }
        } catch (e: Exception) {
            UserProfileResult(
                qqNumber = input.qqNumber, nickname = "", sex = "", age = 0,
                found = false, error = e.message
            )
        }
    }
}

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

    override val description: String = "查看用户主页/资料。根据QQ号获取用户的基本信息。"

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

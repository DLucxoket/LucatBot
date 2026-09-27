package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.FriendListRequest
import org.dlucxoket.lucatbot.bot.tool.data.FriendListResult
import org.dlucxoket.lucatbot.bot.tool.data.FriendInfo

/**
 * 好友列表工具
 *
 * 查询机器人的好友列表。
 */
object FriendListTool : Tool<FriendListRequest, FriendListResult>() {
    override val name: String = "FriendListTool"
    override val description: String = "查看好友列表。返回所有好友的QQ号和昵称。"
    override val parameterHint: String = "JSON: {\"keyword\": \"可选，搜索关键词\"}"
    override val inputClass: Class<FriendListRequest> = FriendListRequest::class.java

    override fun handle(input: FriendListRequest): FriendListResult {
        val b = bot ?: return FriendListResult(success = false, error = "Bot 未绑定")

        return try {
            val friends = b.friendList?.data?.map {
                FriendInfo(userId = it.userId, nickname = it.nickname ?: "", remark = it.remark ?: "")
            } ?: emptyList()

            val filtered = if (input.keyword.isNotBlank()) {
                friends.filter { it.nickname.contains(input.keyword) || it.remark.contains(input.keyword) }
            } else friends

            FriendListResult(friends = filtered, success = true)
        } catch (e: Exception) {
            FriendListResult(success = false, error = e.message)
        }
    }
}

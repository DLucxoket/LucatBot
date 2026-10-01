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
    override val description: String = "功能：查询机器人的好友列表，返回每个好友的 QQ 号、昵称和备注；keyword 非空时按昵称/备注做本地包含过滤。\n何时使用：需要知道机器人有哪些好友、按名字找人或拿 QQ 号时调用；主流程不自动拉好友列表，日常聊天决策一般用不到。"
    override val parameterHint: String = "JSON: {\"keyword\": \"可选，搜索关键词\"}"
    override val inputClass: Class<FriendListRequest> = FriendListRequest::class.java

    override fun handle(input: FriendListRequest): FriendListResult = runTool(
        onError = { FriendListResult(success = false, error = it) }
    ) { b ->
        val friends = b.friendList?.data?.map {
            FriendInfo(userId = it.userId, nickname = it.nickname ?: "", remark = it.remark ?: "")
        } ?: emptyList()

        val filtered = if (input.keyword.isNotBlank()) {
            friends.filter { it.nickname.contains(input.keyword) || it.remark.contains(input.keyword) }
        } else friends

        FriendListResult(friends = filtered, success = true)
    }
}

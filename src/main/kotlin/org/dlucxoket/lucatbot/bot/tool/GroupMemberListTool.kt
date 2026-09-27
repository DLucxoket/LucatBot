package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.GroupMemberListRequest
import org.dlucxoket.lucatbot.bot.tool.data.GroupMemberListResult
import org.dlucxoket.lucatbot.bot.tool.data.GroupMemberInfo

/**
 * 群成员列表工具
 *
 * 查询指定群的成员列表。
 */
object GroupMemberListTool : Tool<GroupMemberListRequest, GroupMemberListResult>() {
    override val name: String = "GroupMemberListTool"
    override val description: String = "查看群成员列表。返回指定群的所有成员QQ号、昵称和角色。"
    override val parameterHint: String = "JSON: {\"groupId\": 123456}"
    override val inputClass: Class<GroupMemberListRequest> = GroupMemberListRequest::class.java

    override fun handle(input: GroupMemberListRequest): GroupMemberListResult {
        val b = bot ?: return GroupMemberListResult(success = false, error = "Bot 未绑定")

        return try {
            val members = b.getGroupMemberList(input.groupId)?.data?.map {
                GroupMemberInfo(
                    userId = it.userId,
                    nickname = it.nickname ?: "",
                    card = it.card ?: "",
                    role = it.role ?: "member"
                )
            } ?: emptyList()

            GroupMemberListResult(members = members, success = true)
        } catch (e: Exception) {
            GroupMemberListResult(success = false, error = e.message)
        }
    }
}

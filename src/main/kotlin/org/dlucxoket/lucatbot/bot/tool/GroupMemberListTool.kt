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
    override val description: String = "功能：查询指定群的全部成员，返回每个成员的 QQ 号、昵称、群名片和角色（member/admin/owner）。\n何时使用：需要知道群里有谁、按名字找某人的 QQ 号、或确认谁是管理员/群主时调用；日常聊天决策不需要全量成员列表。"
    override val parameterHint: String = "JSON: {\"groupId\": 123456}"
    override val inputClass: Class<GroupMemberListRequest> = GroupMemberListRequest::class.java

    override fun handle(input: GroupMemberListRequest): GroupMemberListResult = runTool(
        onError = { GroupMemberListResult(success = false, error = it) }
    ) { b ->
        val members = b.getGroupMemberList(input.groupId)?.data?.map {
            GroupMemberInfo(
                userId = it.userId,
                nickname = it.nickname ?: "",
                card = it.card ?: "",
                role = it.role ?: "member"
            )
        } ?: emptyList()

        GroupMemberListResult(members = members, success = true)
    }
}

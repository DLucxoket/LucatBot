package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.GroupDetailRequest
import org.dlucxoket.lucatbot.bot.tool.data.GroupDetailResult

/**
 * 群详情工具
 *
 * 查询指定群的基本信息。
 */
object GroupDetailTool : Tool<GroupDetailRequest, GroupDetailResult>() {
    override val name: String = "GroupDetailTool"
    override val description: String = "查看群详情。返回群号、群名、人数等信息。"
    override val parameterHint: String = "JSON: {\"groupId\": 123456}"
    override val inputClass: Class<GroupDetailRequest> = GroupDetailRequest::class.java

    override fun handle(input: GroupDetailRequest): GroupDetailResult {
        val b = bot ?: return GroupDetailResult(success = false, error = "Bot 未绑定")

        return try {
            val info = b.getGroupInfo(input.groupId, false)?.data
            GroupDetailResult(
                groupId = input.groupId,
                groupName = info?.groupName ?: "",
                memberCount = info?.memberCount ?: 0,
                maxMemberCount = info?.maxMemberCount ?: 0,
                success = true
            )
        } catch (e: Exception) {
            GroupDetailResult(success = false, error = e.message)
        }
    }
}

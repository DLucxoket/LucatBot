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
    override val description: String = "功能：按群号查询单个群的详情，返回群号、群名、当前人数和人数上限。\n何时使用：已知群号想确认群名或群规模时调用；只是想找某个群建议改用 GroupListTool 按群名关键词过滤。"
    override val parameterHint: String = "JSON: {\"groupId\": 123456}"
    override val inputClass: Class<GroupDetailRequest> = GroupDetailRequest::class.java

    override fun handle(input: GroupDetailRequest): GroupDetailResult = runTool(
        onError = { GroupDetailResult(success = false, error = it) }
    ) { b ->
        val info = b.getGroupInfo(input.groupId, false)?.data
        GroupDetailResult(
            groupId = input.groupId,
            groupName = info?.groupName ?: "",
            memberCount = info?.memberCount ?: 0,
            maxMemberCount = info?.maxMemberCount ?: 0,
            success = true
        )
    }
}

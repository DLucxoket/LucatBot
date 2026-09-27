package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.GroupListRequest
import org.dlucxoket.lucatbot.bot.tool.data.GroupListResult
import org.dlucxoket.lucatbot.bot.tool.data.GroupInfo

/**
 * 群列表工具
 *
 * 查询机器人所在的群列表。
 */
object GroupListTool : Tool<GroupListRequest, GroupListResult>() {
    override val name: String = "GroupListTool"
    override val description: String = "查看群列表。返回所有群的群号、群名和人数。"
    override val parameterHint: String = "JSON: {\"keyword\": \"可选，搜索群名\"}"
    override val inputClass: Class<GroupListRequest> = GroupListRequest::class.java

    override fun handle(input: GroupListRequest): GroupListResult {
        val b = bot ?: return GroupListResult(success = false, error = "Bot 未绑定")

        return try {
            val groups = b.groupList?.data?.map {
                GroupInfo(groupId = it.groupId, groupName = it.groupName ?: "", memberCount = it.memberCount ?: 0)
            } ?: emptyList()

            val filtered = if (input.keyword.isNotBlank()) {
                groups.filter { it.groupName.contains(input.keyword) }
            } else groups

            GroupListResult(groups = filtered, success = true)
        } catch (e: Exception) {
            GroupListResult(success = false, error = e.message)
        }
    }
}

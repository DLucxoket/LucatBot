package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.*

/** 踢人 */
object GroupKickTool : Tool<GroupKickRequest, GroupKickResult>() {
    override val name = "GroupKickTool"
    override val description = "把某人踢出群聊。需要管理员权限。"
    override val parameterHint = "JSON: {\"groupId\": 123, \"userId\": 456, \"rejectAdd\": false}"
    override val inputClass = GroupKickRequest::class.java
    override fun handle(input: GroupKickRequest): GroupKickResult {
        val b = bot ?: return GroupKickResult(error = "Bot 未绑定")
        return try {
            b.setGroupKick(input.groupId, input.userId, input.rejectAdd)
            GroupKickResult(success = true)
        } catch (e: Exception) { GroupKickResult(error = e.message) }
    }
}

/** 禁言 */
object GroupBanTool : Tool<GroupBanRequest, GroupBanResult>() {
    override val name = "GroupBanTool"
    override val description = "禁言/解除禁言某人。duration=0 解除禁言。需要管理员权限。"
    override val parameterHint = "JSON: {\"groupId\": 123, \"userId\": 456, \"duration\": 60}"
    override val inputClass = GroupBanRequest::class.java
    override fun handle(input: GroupBanRequest): GroupBanResult {
        val b = bot ?: return GroupBanResult(error = "Bot 未绑定")
        return try {
            b.setGroupBan(input.groupId, input.userId, input.duration)
            GroupBanResult(success = true)
        } catch (e: Exception) { GroupBanResult(error = e.message) }
    }
}

/** 全员禁言 */
object GroupWholeBanTool : Tool<GroupWholeBanRequest, GroupWholeBanResult>() {
    override val name = "GroupWholeBanTool"
    override val description = "开启/关闭全员禁言。需要群主权限。"
    override val parameterHint = "JSON: {\"groupId\": 123, \"enable\": true}"
    override val inputClass = GroupWholeBanRequest::class.java
    override fun handle(input: GroupWholeBanRequest): GroupWholeBanResult {
        val b = bot ?: return GroupWholeBanResult(error = "Bot 未绑定")
        return try {
            b.setGroupWholeBan(input.groupId, input.enable)
            GroupWholeBanResult(success = true)
        } catch (e: Exception) { GroupWholeBanResult(error = e.message) }
    }
}

/** 设管理员 */
object GroupAdminTool : Tool<GroupAdminRequest, GroupAdminResult>() {
    override val name = "GroupAdminTool"
    override val description = "设置/取消某人为群管理员。需要群主权限。"
    override val parameterHint = "JSON: {\"groupId\": 123, \"userId\": 456, \"enable\": true}"
    override val inputClass = GroupAdminRequest::class.java
    override fun handle(input: GroupAdminRequest): GroupAdminResult {
        val b = bot ?: return GroupAdminResult(error = "Bot 未绑定")
        return try {
            b.setGroupAdmin(input.groupId, input.userId, input.enable)
            GroupAdminResult(success = true)
        } catch (e: Exception) { GroupAdminResult(error = e.message) }
    }
}

/** 改群名 */
object GroupNameTool : Tool<GroupNameRequest, GroupNameResult>() {
    override val name = "GroupNameTool"
    override val description = "修改群名称。需要管理员权限。"
    override val parameterHint = "JSON: {\"groupId\": 123, \"name\": \"新群名\"}"
    override val inputClass = GroupNameRequest::class.java
    override fun handle(input: GroupNameRequest): GroupNameResult {
        val b = bot ?: return GroupNameResult(error = "Bot 未绑定")
        return try {
            b.setGroupName(input.groupId, input.name)
            GroupNameResult(success = true)
        } catch (e: Exception) { GroupNameResult(error = e.message) }
    }
}

/** 改群名片 */
object GroupCardTool : Tool<GroupCardRequest, GroupCardResult>() {
    override val name = "GroupCardTool"
    override val description = "修改某人在群里的名片/昵称。"
    override val parameterHint = "JSON: {\"groupId\": 123, \"userId\": 456, \"card\": \"新名片\"}"
    override val inputClass = GroupCardRequest::class.java
    override fun handle(input: GroupCardRequest): GroupCardResult {
        val b = bot ?: return GroupCardResult(error = "Bot 未绑定")
        return try {
            b.setGroupCard(input.groupId, input.userId, input.card)
            GroupCardResult(success = true)
        } catch (e: Exception) { GroupCardResult(error = e.message) }
    }
}

/** 发群公告 */
object GroupNoticeTool : Tool<GroupNoticeRequest, GroupNoticeResult>() {
    override val name = "GroupNoticeTool"
    override val description = "发布群公告。需要管理员权限。"
    override val parameterHint = "JSON: {\"groupId\": 123, \"content\": \"公告内容\"}"
    override val inputClass = GroupNoticeRequest::class.java
    override fun handle(input: GroupNoticeRequest): GroupNoticeResult {
        val b = bot ?: return GroupNoticeResult(error = "Bot 未绑定")
        return try {
            b.sendGroupNotice(input.groupId, input.content)
            GroupNoticeResult(success = true)
        } catch (e: Exception) { GroupNoticeResult(error = e.message) }
    }
}

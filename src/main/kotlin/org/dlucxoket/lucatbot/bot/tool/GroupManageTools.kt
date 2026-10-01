package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.*

/** 踢人 */
object GroupKickTool : Tool<GroupKickRequest, GroupKickResult>() {
    override val name: String = "GroupKickTool"
    override val description: String = "功能：把指定成员踢出群聊（rejectAdd=true 时同时拒绝其再次入群），返回是否成功。\n何时使用：破坏性操作，需要机器人具备群主或管理员权限；仅在明确的管理场景（如踢出违规成员）谨慎调用，日常聊天绝不使用。"
    override val parameterHint: String = "JSON: {\"groupId\": 123, \"userId\": 456, \"rejectAdd\": false}"
    override val inputClass = GroupKickRequest::class.java
    override fun handle(input: GroupKickRequest): GroupKickResult = runTool(
        onError = { GroupKickResult(success = false, error = it) }
    ) { b ->
        b.setGroupKick(input.groupId, input.userId, input.rejectAdd)
        GroupKickResult(success = true)
    }
}

/** 禁言 */
object GroupBanTool : Tool<GroupBanRequest, GroupBanResult>() {
    override val name: String = "GroupBanTool"
    override val description: String = "功能：禁言或解除禁言单个群成员，duration 为禁言秒数（0 表示解除禁言），返回是否成功。\n何时使用：破坏性操作，需要机器人具备管理员权限；仅在明确的管理场景谨慎调用，不要对普通聊天对象使用，duration=0 用于解除禁言。"
    override val parameterHint: String = "JSON: {\"groupId\": 123, \"userId\": 456, \"duration\": 60}"
    override val inputClass = GroupBanRequest::class.java
    override fun handle(input: GroupBanRequest): GroupBanResult = runTool(
        onError = { GroupBanResult(success = false, error = it) }
    ) { b ->
        b.setGroupBan(input.groupId, input.userId, input.duration)
        GroupBanResult(success = true)
    }
}

/** 全员禁言 */
object GroupWholeBanTool : Tool<GroupWholeBanRequest, GroupWholeBanResult>() {
    override val name: String = "GroupWholeBanTool"
    override val description: String = "功能：开启或关闭全群禁言（enable=true 开启、false 关闭），返回是否成功。\n何时使用：破坏性操作，影响全体成员且需要群主/管理员权限；除非有明确的管理需求，否则不要调用。"
    override val parameterHint: String = "JSON: {\"groupId\": 123, \"enable\": true}"
    override val inputClass = GroupWholeBanRequest::class.java
    override fun handle(input: GroupWholeBanRequest): GroupWholeBanResult = runTool(
        onError = { GroupWholeBanResult(success = false, error = it) }
    ) { b ->
        b.setGroupWholeBan(input.groupId, input.enable)
        GroupWholeBanResult(success = true)
    }
}

/** 设管理员 */
object GroupAdminTool : Tool<GroupAdminRequest, GroupAdminResult>() {
    override val name: String = "GroupAdminTool"
    override val description: String = "功能：设置或取消某人的群管理员身份（enable=true 设为管理员、false 取消），返回是否成功。\n何时使用：破坏性且敏感的操作，需要群主权限，会直接改变群内权限结构；仅在明确的管理场景谨慎调用。"
    override val parameterHint: String = "JSON: {\"groupId\": 123, \"userId\": 456, \"enable\": true}"
    override val inputClass = GroupAdminRequest::class.java
    override fun handle(input: GroupAdminRequest): GroupAdminResult = runTool(
        onError = { GroupAdminResult(success = false, error = it) }
    ) { b ->
        b.setGroupAdmin(input.groupId, input.userId, input.enable)
        GroupAdminResult(success = true)
    }
}

/** 改群名 */
object GroupNameTool : Tool<GroupNameRequest, GroupNameResult>() {
    override val name: String = "GroupNameTool"
    override val description: String = "功能：修改指定群的群名称，返回是否成功。\n何时使用：破坏性且公开可见的变更，需要管理员权限、全群成员都能看到；仅在明确要求改名时谨慎调用。"
    override val parameterHint: String = "JSON: {\"groupId\": 123, \"name\": \"新群名\"}"
    override val inputClass = GroupNameRequest::class.java
    override fun handle(input: GroupNameRequest): GroupNameResult = runTool(
        onError = { GroupNameResult(success = false, error = it) }
    ) { b ->
        b.setGroupName(input.groupId, input.name)
        GroupNameResult(success = true)
    }
}

/** 改群名片 */
object GroupCardTool : Tool<GroupCardRequest, GroupCardResult>() {
    override val name: String = "GroupCardTool"
    override val description: String = "功能：修改某人在群里的名片（群昵称），返回是否成功；修改他人名片需要机器人具备管理员权限。\n何时使用：管理类操作，普通聊天用不到；仅在明确要给某人改群名片时调用，改他人名片可能因权限不足失败。"
    override val parameterHint: String = "JSON: {\"groupId\": 123, \"userId\": 456, \"card\": \"新名片\"}"
    override val inputClass = GroupCardRequest::class.java
    override fun handle(input: GroupCardRequest): GroupCardResult = runTool(
        onError = { GroupCardResult(success = false, error = it) }
    ) { b ->
        b.setGroupCard(input.groupId, input.userId, input.card)
        GroupCardResult(success = true)
    }
}

/** 发群公告 */
object GroupNoticeTool : Tool<GroupNoticeRequest, GroupNoticeResult>() {
    override val name: String = "GroupNoticeTool"
    override val description: String = "功能：发布群公告，返回是否成功。\n何时使用：破坏性且公开的操作，需要管理员权限，公告会推送给全群；仅在确有公告需求时谨慎调用，不要随意发公告。"
    override val parameterHint: String = "JSON: {\"groupId\": 123, \"content\": \"公告内容\"}"
    override val inputClass = GroupNoticeRequest::class.java
    override fun handle(input: GroupNoticeRequest): GroupNoticeResult = runTool(
        onError = { GroupNoticeResult(success = false, error = it) }
    ) { b ->
        b.sendGroupNotice(input.groupId, input.content)
        GroupNoticeResult(success = true)
    }
}

package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.*

/** 按 ID 查消息 */
object GetMsgTool : Tool<GetMsgRequest, GetMsgResult>() {
    override val name: String = "GetMsgTool"
    override val description: String = "功能：按消息 ID 向 OneBot 查询单条消息，返回该消息的内容与发送者昵称。\n何时使用：聊天记录或事件里带有 messageId、需要还原某条具体消息时调用；查连续的前后历史用 ChatHistoryTool。"
    override val parameterHint: String = "JSON: {\"messageId\": 123}"
    override val inputClass = GetMsgRequest::class.java
    override fun handle(input: GetMsgRequest): GetMsgResult {
        val b = bot ?: return GetMsgResult(error = "Bot 未绑定")
        return try {
            val msg = b.getMsg(input.messageId)?.data
            GetMsgResult(
                content = msg?.message ?: "",
                sender = msg?.sender?.nickname ?: "",
                success = true
            )
        } catch (e: Exception) { GetMsgResult(error = e.message) }
    }
}

/** 点赞 */
object SendLikeTool : Tool<SendLikeRequest, SendLikeResult>() {
    override val name: String = "SendLikeTool"
    override val description: String = "功能：给指定 QQ 用户点赞（times 为点赞次数），返回是否成功。\n何时使用：模拟真人给好友点赞的轻量互动，适合闲暇时增进关系；不是聊天必需，按需少量调用。"
    override val parameterHint: String = "JSON: {\"userId\": 123, \"times\": 10}"
    override val inputClass = SendLikeRequest::class.java
    override fun handle(input: SendLikeRequest): SendLikeResult {
        val b = bot ?: return SendLikeResult(error = "Bot 未绑定")
        return try {
            b.sendLike(input.userId, input.times)
            SendLikeResult(success = true)
        } catch (e: Exception) { SendLikeResult(error = e.message) }
    }
}

/** 消息表情回应 */
object EmojiLikeTool : Tool<EmojiLikeRequest, EmojiLikeResult>() {
    override val name: String = "EmojiLikeTool"
    override val description: String = "功能：对指定 messageId 的消息设置或取消 QQ 表情回应，code 为表情代码字符串，isSet=true 设置、false 取消，返回是否成功。\n何时使用：想对某条消息点个表情回应（如点赞表情）表示看到或赞同时调用，比文字回复更轻量；不是每次回复都必要。"
    override val parameterHint: String = "JSON: {\"messageId\": 123, \"code\": \"1\", \"isSet\": true}"
    override val inputClass = EmojiLikeRequest::class.java
    override fun handle(input: EmojiLikeRequest): EmojiLikeResult {
        val b = bot ?: return EmojiLikeResult(error = "Bot 未绑定")
        return try {
            b.setMsgEmojiLike(input.messageId, input.code, input.isSet)
            EmojiLikeResult(success = true)
        } catch (e: Exception) { EmojiLikeResult(error = e.message) }
    }
}

/** 登录信息 */
object LoginInfoTool : Tool<LoginInfoRequest, LoginInfoResult>() {
    override val name: String = "LoginInfoTool"
    override val description: String = "功能：查询机器人自己的登录信息，返回 QQ 号与昵称。\n何时使用：需要确认机器人当前登录的账号身份时调用；机器人资料已注入决策 prompt 的「你的信息」，一般无需重复查询。"
    override val parameterHint: String = "JSON: {}"
    override val inputClass = LoginInfoRequest::class.java
    override fun handle(input: LoginInfoRequest): LoginInfoResult {
        val b = bot ?: return LoginInfoResult(error = "Bot 未绑定")
        return try {
            val info = b.loginInfo?.data
            LoginInfoResult(
                userId = info?.userId ?: 0,
                nickname = info?.nickname ?: "",
                success = true
            )
        } catch (e: Exception) { LoginInfoResult(error = e.message) }
    }
}

/** 机器人状态 */
object BotStatusTool : Tool<BotStatusRequest, BotStatusResult>() {
    override val name: String = "BotStatusTool"
    override val description: String = "功能：查询机器人账号当前是否在线，返回 online=true/false。\n何时使用：怀疑掉线、想确认机器人在线状态时调用；正常主流程无需关心，不要频繁调用。"
    override val parameterHint: String = "JSON: {}"
    override val inputClass = BotStatusRequest::class.java
    override fun handle(input: BotStatusRequest): BotStatusResult {
        val b = bot ?: return BotStatusResult(error = "Bot 未绑定")
        return try {
            val status = b.status?.data
            BotStatusResult(online = status?.online ?: false, success = true)
        } catch (e: Exception) { BotStatusResult(error = e.message) }
    }
}

/** 群打卡 */
object GroupSignTool : Tool<GroupSignRequest, GroupSignResult>() {
    override val name: String = "GroupSignTool"
    override val description: String = "功能：在指定群内执行打卡签到，返回是否成功。\n何时使用：群打卡是偶发的轻互动（如每日打卡混脸熟），由智能体按需主动调用；日常聊天主流程不会自动打卡。"
    override val parameterHint: String = "JSON: {\"groupId\": 123}"
    override val inputClass = GroupSignRequest::class.java
    override fun handle(input: GroupSignRequest): GroupSignResult {
        val b = bot ?: return GroupSignResult(error = "Bot 未绑定")
        return try {
            b.sendGroupSign(input.groupId)
            GroupSignResult(success = true)
        } catch (e: Exception) { GroupSignResult(error = e.message) }
    }
}

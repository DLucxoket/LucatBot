package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.tool.data.*

/** 按 ID 查消息 */
object GetMsgTool : Tool<GetMsgRequest, GetMsgResult>() {
    override val name: String = "GetMsgTool"
    override val description: String = "按消息ID查询消息内容和发送者。"
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
    override val description: String = "给某人点赞。模拟真人给朋友点赞。"
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
    override val description: String = "对某条消息进行表情回应。模拟真人对消息点表情。"
    override val parameterHint: String = "JSON: {\"messageId\": 123, \"code\": 1, \"isSet\": true}"
    override val inputClass = EmojiLikeRequest::class.java
    override fun handle(input: EmojiLikeRequest): EmojiLikeResult {
        val b = bot ?: return EmojiLikeResult(error = "Bot 未绑定")
        return try {
            b.setMsgEmojiLike(input.messageId, "1", true)  // 默认点赞
            EmojiLikeResult(success = true)
        } catch (e: Exception) { EmojiLikeResult(error = e.message) }
    }
}

/** 登录信息 */
object LoginInfoTool : Tool<LoginInfoRequest, LoginInfoResult>() {
    override val name: String = "LoginInfoTool"
    override val description: String = "查看机器人自己的账号信息（QQ号、昵称）。"
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
    override val description: String = "查看机器人在线状态。"
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
    override val description: String = "群打卡签到。"
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

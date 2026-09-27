package org.dlucxoket.lucatbot.bot.tool.data

import com.fasterxml.jackson.annotation.JsonProperty

// ========== 群管理 ==========

data class GroupKickRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("rejectAdd") val rejectAdd: Boolean = false
)
data class GroupKickResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class GroupBanRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("duration") val duration: Int = 0  // 秒，0=解除禁言
)
data class GroupBanResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class GroupWholeBanRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("enable") val enable: Boolean = true
)
data class GroupWholeBanResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class GroupAdminRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("enable") val enable: Boolean = true
)
data class GroupAdminResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class GroupNameRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("name") val name: String = ""
)
data class GroupNameResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class GroupCardRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("card") val card: String = ""
)
data class GroupCardResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class GroupNoticeRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("content") val content: String = ""
)
data class GroupNoticeResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

// ========== 消息操作 ==========

data class GetMsgRequest(
    @JsonProperty("messageId") val messageId: Int = 0
)
data class GetMsgResult(
    @JsonProperty("content") val content: String = "",
    @JsonProperty("sender") val sender: String = "",
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class SendLikeRequest(
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("times") val times: Int = 10
)
data class SendLikeResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class EmojiLikeRequest(
    @JsonProperty("messageId") val messageId: Int = 0
)
data class EmojiLikeResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

// ========== 账号/杂项 ==========

data class LoginInfoRequest(
    @JsonProperty("dummy") val dummy: String = ""
)
data class LoginInfoResult(
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("nickname") val nickname: String = "",
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class BotStatusRequest(
    @JsonProperty("dummy") val dummy: String = ""
)
data class BotStatusResult(
    @JsonProperty("online") val online: Boolean = false,
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

data class GroupSignRequest(
    @JsonProperty("groupId") val groupId: Long = 0
)
data class GroupSignResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

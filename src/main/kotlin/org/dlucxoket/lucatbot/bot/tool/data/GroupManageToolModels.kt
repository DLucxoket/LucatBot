package org.dlucxoket.lucatbot.bot.tool.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ========== 群管理 ==========

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupKickRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("rejectAdd") val rejectAdd: Boolean = false
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupKickResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupBanRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("duration") val duration: Int = 0  // 秒，0=解除禁言
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupBanResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupWholeBanRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("enable") val enable: Boolean = true
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupWholeBanResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupAdminRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("enable") val enable: Boolean = true
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupAdminResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupNameRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("name") val name: String = ""
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupNameResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupCardRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("card") val card: String = ""
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupCardResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupNoticeRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("content") val content: String = ""
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupNoticeResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

// ========== 消息操作 ==========

@JsonIgnoreProperties(ignoreUnknown = true)
data class GetMsgRequest(
    @JsonProperty("messageId") val messageId: Int = 0
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class GetMsgResult(
    @JsonProperty("content") val content: String = "",
    @JsonProperty("sender") val sender: String = "",
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SendLikeRequest(
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("times") val times: Int = 10
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class SendLikeResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmojiLikeRequest(
    @JsonProperty("messageId") val messageId: Int = 0,
    @JsonProperty("code") val code: String = "1",
    @JsonProperty("isSet") val isSet: Boolean = true
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class EmojiLikeResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

// ========== 账号/杂项 ==========

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoginInfoRequest(
    @JsonProperty("dummy") val dummy: String = ""
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoginInfoResult(
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("nickname") val nickname: String = "",
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BotStatusRequest(
    @JsonProperty("dummy") val dummy: String = ""
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class BotStatusResult(
    @JsonProperty("online") val online: Boolean = false,
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupSignRequest(
    @JsonProperty("groupId") val groupId: Long = 0
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupSignResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

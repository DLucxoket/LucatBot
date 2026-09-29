package org.dlucxoket.lucatbot.bot.tool.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ========== 群管理 ==========

/** 踢出群成员请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupKickRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("rejectAdd") val rejectAdd: Boolean = false
)

/** 踢出群成员结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupKickResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 禁言/解禁请求（duration 单位秒，0 = 解除禁言） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupBanRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("duration") val duration: Int = 0  // 秒，0=解除禁言
)

/** 禁言/解禁结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupBanResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 全员禁言请求（enable true = 开启全员禁言） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupWholeBanRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("enable") val enable: Boolean = true
)

/** 全员禁言结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupWholeBanResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 设置/取消群管理员请求（enable true = 设为管理员） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupAdminRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("enable") val enable: Boolean = true
)

/** 设置/取消群管理员结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupAdminResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 修改群名称请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupNameRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("name") val name: String = ""
)

/** 修改群名称结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupNameResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 修改群成员名片（群内备注名）请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupCardRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("card") val card: String = ""
)

/** 修改群成员名片结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupCardResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 发布群公告请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupNoticeRequest(
    @JsonProperty("groupId") val groupId: Long = 0,
    @JsonProperty("content") val content: String = ""
)

/** 发布群公告结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupNoticeResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

// ========== 消息操作 ==========

/** 按消息 ID 获取消息内容请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GetMsgRequest(
    @JsonProperty("messageId") val messageId: Int = 0
)

/** 获取消息内容结果（正文 + 发送者） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GetMsgResult(
    @JsonProperty("content") val content: String = "",
    @JsonProperty("sender") val sender: String = "",
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 给用户点赞请求（times = 点赞次数） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SendLikeRequest(
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("times") val times: Int = 10
)

/** 给用户点赞结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SendLikeResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 消息表情回应请求（对某条消息点 QQ 表情赞；code = 表情代码，isSet = 取消与否） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EmojiLikeRequest(
    @JsonProperty("messageId") val messageId: Int = 0,
    @JsonProperty("code") val code: String = "1",
    @JsonProperty("isSet") val isSet: Boolean = true
)

/** 消息表情回应结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EmojiLikeResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

// ========== 账号/杂项 ==========

/** 查询机器人登录信息请求（无参占位字段） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoginInfoRequest(
    @JsonProperty("dummy") val dummy: String = ""
)

/** 登录信息结果（机器人 QQ 号与昵称） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LoginInfoResult(
    @JsonProperty("userId") val userId: Long = 0,
    @JsonProperty("nickname") val nickname: String = "",
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 查询机器人在线状态请求（无参占位字段） */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BotStatusRequest(
    @JsonProperty("dummy") val dummy: String = ""
)

/** 机器人在线状态结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BotStatusResult(
    @JsonProperty("online") val online: Boolean = false,
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

/** 群签到请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupSignRequest(
    @JsonProperty("groupId") val groupId: Long = 0
)

/** 群签到结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupSignResult(
    @JsonProperty("success") val success: Boolean = true,
    @JsonProperty("error") val error: String? = null
)

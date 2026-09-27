package org.dlucxoket.lucatbot.bot.tool.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

// ========== 好友 ==========

/** 好友列表请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendListRequest(
    @JsonProperty("keyword")
    val keyword: String = ""
)

/** 好友列表响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendListResult(
    @JsonProperty("friends")
    val friends: List<FriendInfo> = emptyList(),
    @JsonProperty("success")
    val success: Boolean = true,
    @JsonProperty("error")
    val error: String? = null
)

/** 好友信息 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FriendInfo(
    @JsonProperty("userId")
    val userId: Long = 0,
    @JsonProperty("nickname")
    val nickname: String = "",
    @JsonProperty("remark")
    val remark: String = ""
)

// ========== 群 ==========

/** 群列表请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupListRequest(
    @JsonProperty("keyword")
    val keyword: String = ""
)

/** 群列表响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupListResult(
    @JsonProperty("groups")
    val groups: List<GroupInfo> = emptyList(),
    @JsonProperty("success")
    val success: Boolean = true,
    @JsonProperty("error")
    val error: String? = null
)

/** 群信息 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupInfo(
    @JsonProperty("groupId")
    val groupId: Long = 0,
    @JsonProperty("groupName")
    val groupName: String = "",
    @JsonProperty("memberCount")
    val memberCount: Int = 0
)

/** 群成员列表请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupMemberListRequest(
    @JsonProperty("groupId")
    val groupId: Long = 0
)

/** 群成员列表响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupMemberListResult(
    @JsonProperty("members")
    val members: List<GroupMemberInfo> = emptyList(),
    @JsonProperty("success")
    val success: Boolean = true,
    @JsonProperty("error")
    val error: String? = null
)

/** 群成员信息 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupMemberInfo(
    @JsonProperty("userId")
    val userId: Long = 0,
    @JsonProperty("nickname")
    val nickname: String = "",
    @JsonProperty("card")
    val card: String = "",
    @JsonProperty("role")
    val role: String = ""
)

/** 群详情请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupDetailRequest(
    @JsonProperty("groupId")
    val groupId: Long = 0
)

/** 群详情响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupDetailResult(
    @JsonProperty("groupId")
    val groupId: Long = 0,
    @JsonProperty("groupName")
    val groupName: String = "",
    @JsonProperty("memberCount")
    val memberCount: Int = 0,
    @JsonProperty("maxMemberCount")
    val maxMemberCount: Int = 0,
    @JsonProperty("success")
    val success: Boolean = true,
    @JsonProperty("error")
    val error: String? = null
)

// ========== 消息操作 ==========

/** 撤回消息请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RecallMsgRequest(
    @JsonProperty("messageId")
    val messageId: Int = 0
)

/** 撤回消息响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RecallMsgResult(
    @JsonProperty("success")
    val success: Boolean = true,
    @JsonProperty("error")
    val error: String? = null
)

/** 戳一戳请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PokeRequest(
    @JsonProperty("conversationId")
    val conversationId: String = "",
    @JsonProperty("userId")
    val userId: Long = 0
)

/** 戳一戳响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PokeResult(
    @JsonProperty("success")
    val success: Boolean = true,
    @JsonProperty("error")
    val error: String? = null
)

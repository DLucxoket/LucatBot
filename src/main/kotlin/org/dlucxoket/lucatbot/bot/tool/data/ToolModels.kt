package org.dlucxoket.lucatbot.bot.tool.data
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** 查询用户资料的请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserProfileRequest(val qqNumber: Long)

/** 查询用户资料的响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class UserProfileResult(
    val qqNumber: Long,
    val nickname: String,
    val sex: String,
    val age: Int,
    val found: Boolean,
    val error: String? = null
)

/** 查询聊天记录的请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatHistoryRequest(
    val targetId: Long = 0,
    val count: Int = 5,
    val type: String = "private"  // "group" 或 "private"
)

/** 查询聊天记录的响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatHistoryResult(
    val targetId: Long,
    val messages: List<HistoryMessageItem> = emptyList(),
    val success: Boolean = true,
    val error: String? = null
)

/** 聊天记录条目 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HistoryMessageItem(
    val sender: String,
    val content: String,
    val timestamp: Long = 0L,
    /** 发送者 QQ 号（引用解析：@QQ → 昵称 靠它建索引） */
    val senderId: Long = 0L,
    /** OneBot message_id（引用解析：回复 #id → 具体消息；可空） */
    val messageId: Long? = null
)

/** 查询消息列表的请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MessageListRequest(
    val targetId: Long = 0,
    val type: String = "private"  // "group" 或 "private"
)

/** 查询消息列表的响应 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MessageListResult(
    val targetId: Long,
    val messageCount: Int = 0,
    val lastMessage: String = "",
    val success: Boolean = true,
    val error: String? = null
)

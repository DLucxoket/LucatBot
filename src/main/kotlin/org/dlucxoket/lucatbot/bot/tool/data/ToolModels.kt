package org.dlucxoket.lucatbot.bot.tool.data

/** 查询用户资料的请求 */
data class UserProfileRequest(val qqNumber: Long)

/** 查询用户资料的响应 */
data class UserProfileResult(
    val qqNumber: Long,
    val nickname: String,
    val sex: String,
    val age: Int,
    val found: Boolean,
    val error: String? = null
)

/** 查询聊天记录的请求 */
data class ChatHistoryRequest(
    val targetId: Long,
    val count: Int = 5
)

/** 查询聊天记录的响应 */
data class ChatHistoryResult(
    val targetId: Long,
    val messages: List<HistoryMessageItem> = emptyList(),
    val success: Boolean = true,
    val error: String? = null
)

/** 聊天记录条目 */
data class HistoryMessageItem(
    val sender: String,
    val content: String,
    val timestamp: Long = 0L
)

/** 查询消息列表的请求 */
data class MessageListRequest(val targetId: Long)

/** 查询消息列表的响应 */
data class MessageListResult(
    val targetId: Long,
    val messageCount: Int = 0,
    val success: Boolean = true,
    val error: String? = null
)

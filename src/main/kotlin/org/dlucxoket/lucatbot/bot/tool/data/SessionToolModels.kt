package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.annotation.JsonProperty

/** 未读概况请求 */
data class UnreadSummaryRequest(
    @JsonProperty("filter")
    val filter: String = ""
)

/** 未读概况响应 */
data class UnreadSummaryResult(
    @JsonProperty("conversations")
    val conversations: List<UnreadConversation> = emptyList(),

    @JsonProperty("success")
    val success: Boolean = true,

    @JsonProperty("error")
    val error: String? = null
)

/** 单个会话的未读信息 */
data class UnreadConversation(
    @JsonProperty("conversationId")
    val conversationId: String = "",

    @JsonProperty("count")
    val count: Int = 0,

    @JsonProperty("isAtMe")
    val isAtMe: Boolean = false,

    @JsonProperty("isAtAll")
    val isAtAll: Boolean = false
)

/** 获取会话内容请求 */
data class GetSessionContentRequest(
    @JsonProperty("conversationId")
    val conversationId: String = "",

    @JsonProperty("limit")
    val limit: Int = 10
)

/** 获取会话内容响应 */
data class GetSessionContentResult(
    @JsonProperty("messages")
    val messages: List<SessionMessage> = emptyList(),

    @JsonProperty("success")
    val success: Boolean = true,

    @JsonProperty("error")
    val error: String? = null
)

/** 会话消息 */
data class SessionMessage(
    @JsonProperty("sender")
    val sender: String = "",

    @JsonProperty("content")
    val content: String = "",

    @JsonProperty("timestamp")
    val timestamp: Long = 0L
)

/** 发送消息请求 */
data class SendMessageRequest(
    @JsonProperty("conversationId")
    val conversationId: String = "",

    @JsonProperty("content")
    val content: String = ""
)

/** 发送消息响应 */
data class SendMessageResult(
    @JsonProperty("success")
    val success: Boolean = true,

    @JsonProperty("error")
    val error: String? = null
)

/** 发送表情请求 */
data class SendEmojiRequest(
    @JsonProperty("conversationId")
    val conversationId: String = "",

    @JsonProperty("emojiName")
    val emojiName: String = ""
)

/** 发送表情响应 */
data class SendEmojiResult(
    @JsonProperty("success")
    val success: Boolean = true,

    @JsonProperty("error")
    val error: String? = null
)

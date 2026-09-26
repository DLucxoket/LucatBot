package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 智能回复 Agent 的请求
 */
data class ReplyRequest(
    @JsonProperty("context")
    val context: String = "",

    @JsonProperty("messages")
    val messages: List<ChatMessageItem> = emptyList(),

    @JsonProperty("personality")
    val personality: String = "",

    @JsonProperty("mood")
    val mood: String = ""
)

/**
 * 聊天消息条目
 */
data class ChatMessageItem(
    @JsonProperty("sender")
    val sender: String = "",

    @JsonProperty("content")
    val content: String = "",

    @JsonProperty("timestamp")
    val timestamp: Long = 0L
)

/**
 * 智能回复 Agent 的响应
 */
data class ReplyResult(
    @JsonProperty("reply")
    val reply: String = "",

    @JsonProperty("action")
    val action: String = "send",

    @JsonProperty("confidence")
    val confidence: Double = 1.0
)

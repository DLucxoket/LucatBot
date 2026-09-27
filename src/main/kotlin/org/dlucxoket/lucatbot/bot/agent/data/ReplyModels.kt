package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 智能回复 Agent 的请求
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
@JsonIgnoreProperties(ignoreUnknown = true)
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
@JsonIgnoreProperties(ignoreUnknown = true)
data class ReplyResult(
    @JsonProperty("reply")
    val reply: String = "",

    @JsonProperty("action")
    val action: String = "send",

    @JsonProperty("confidence")
    val confidence: Double = 1.0
)

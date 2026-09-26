package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 回声 Agent 的请求（测试用）
 */
data class EchoRequest(
    @JsonProperty("text")
    val text: String
)

/**
 * 回声 Agent 的响应
 */
data class EchoResult(
    @JsonProperty("echo")
    val echo: String
)

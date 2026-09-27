package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 回声 Agent 的请求（测试用）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EchoRequest(
    @JsonProperty("text")
    val text: String = ""
)

/**
 * 回声 Agent 的响应
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EchoResult(
    @JsonProperty("echo")
    val echo: String = ""
)

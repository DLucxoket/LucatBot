package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.dlucxoket.lucatbot.bot.agent.AgentPlan

/**
 * DispatcherAgent 的请求
 *
 * context 为自然语言上下文（可含会话摘要），不是 JSON 载荷。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DispatcherRequest(
    @JsonProperty("context")
    val context: String
)

/**
 * DispatcherAgent 的响应
 *
 * plans 中的 input 已是 Kotlin Map 对象，不含 JSON 字符串。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DispatcherResult(
    @JsonProperty("done")
    val done: Boolean = false,

    @JsonProperty("plans")
    val plans: List<AgentPlan> = emptyList()
)

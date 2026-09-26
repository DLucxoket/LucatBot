package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonProperty
import org.dlucxoket.lucatbot.bot.agent.AgentPlan

/**
 * DispatcherAgent 的请求
 */
data class DispatcherRequest(
    @JsonProperty("context")
    val context: String
)

/**
 * DispatcherAgent 的响应
 */
data class DispatcherResult(
    @JsonProperty("done")
    val done: Boolean = false,

    @JsonProperty("plans")
    val plans: List<AgentPlan> = emptyList()
)

package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.dlucxoket.lucatbot.bot.agent.AgentPlan

/**
 * DispatcherAgent 的请求
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DispatcherRequest(
    @JsonProperty("context")
    val context: String
)

/**
 * DispatcherAgent 的响应
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DispatcherResult(
    @JsonProperty("done")
    val done: Boolean = false,

    @JsonProperty("plans")
    val plans: List<AgentPlan> = emptyList()
)

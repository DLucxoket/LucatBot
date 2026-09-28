package org.dlucxoket.lucatbot.bot.agent

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Agent 调用计划
 *
 * 表示 DispatcherAgent 决策输出中的一个待执行调用。
 *
 * **OOP 约定**：input 是 Kotlin 对象（Map），不是 JSON 字符串。
 * JSON 序列化/反序列化只发生在 LLM 边界（AgentPlanParser）。
 *
 * @param agentName 目标 Agent/Tool 的名称（JSON key: "agent"）
 * @param input     输入数据（Kotlin Map 对象）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentPlan(
    @JsonProperty("agent")
    val agentName: String,

    @JsonProperty("input")
    val input: Map<String, Any?> = emptyMap()
)

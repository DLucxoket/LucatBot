package org.dlucxoket.lucatbot.bot.agent

/**
 * Agent 调用计划
 *
 * 表示 DispatcherAgent 决策输出中的一个待执行调用。
 *
 * @param agentName 目标 Agent 的类名（简单名）
 * @param input     JSON 字符串，由 DispatcherAgent 显式指定的输入数据
 */
data class AgentPlan(
    val agentName: String,
    val input: String
)

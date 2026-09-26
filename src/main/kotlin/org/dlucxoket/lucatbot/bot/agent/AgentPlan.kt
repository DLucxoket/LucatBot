package org.dlucxoket.lucatbot.bot.agent

/**
 * Agent 调用计划
 *
 * 表示 DispatcherAgent 决策输出中的一个待执行调用：
 * 指定调用哪个 Agent、给它什么输入。
 *
 * @param agentName 目标 Agent 的类名（简单名）
 * @param input     由 DispatcherAgent 显式指定的输入内容
 */
data class AgentPlan(
    val agentName: String,
    val input: String
)

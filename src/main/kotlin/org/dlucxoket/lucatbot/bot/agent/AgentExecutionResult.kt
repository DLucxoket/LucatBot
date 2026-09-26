package org.dlucxoket.lucatbot.bot.agent

/**
 * Agent 执行结果
 *
 * 记录单个 Agent 执行一次调用的结果，无论成功或失败。
 *
 * @param agentName 执行的 Agent 类名
 * @param input     传入的输入
 * @param output    执行结果文本（失败时为空字符串）
 * @param success   是否执行成功
 * @param error     失败时的错误信息（成功时为 null）
 */
data class AgentExecutionResult(
    val agentName: String,
    val input: String,
    val output: String,
    val success: Boolean,
    val error: String? = null
)

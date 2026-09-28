package org.dlucxoket.lucatbot.bot.agent

/**
 * Agent 执行结果
 *
 * 记录单个 Agent/Tool 执行一次调用的结果，无论成功或失败。
 *
 * **OOP 约定**：input / output 是 Kotlin 对象，不是 JSON 字符串。
 * input 为调度计划里的 Map；output 为 handle() 返回的 data class 实例。
 *
 * @param agentName 执行的 Agent/Tool 名称
 * @param input     传入的输入对象
 * @param output    输出对象（失败时为 null）
 * @param success   是否执行成功
 * @param error     失败时的错误信息（成功时为 null）
 */
data class AgentExecutionResult(
    val agentName: String,
    val input: Map<String, Any?> = emptyMap(),
    val output: Any? = null,
    val success: Boolean,
    val error: String? = null
)

package org.dlucxoket.lucatbot.bot.agent.data

/**
 * Agent 通用调度上下文
 *
 * 模拟真人行为所需的完整决策信息。
 * 在 LLM API 边界序列化为 JSON，进入 Agent 后反序列化为 OOP 对象。
 */
data class AgentContext(
    /** 初始用户输入 */
    val initialInput: String,

    /** 当前调度轮次 */
    val round: Int = 0,

    /** 之前执行的结果 */
    val results: List<AgentResultEntry> = emptyList()
)

/**
 * 单个 Agent 执行结果条目
 */
data class AgentResultEntry(
    val agent: String,
    val input: String,
    val output: String,
    val success: Boolean,
    val error: String? = null
)

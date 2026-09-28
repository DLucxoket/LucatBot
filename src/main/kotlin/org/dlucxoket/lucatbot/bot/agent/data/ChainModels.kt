package org.dlucxoket.lucatbot.bot.agent.data
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 调度链结果汇总
 *
 * 替代 AgentChain 中的手拼 JSON，保持 OOP 边界原则。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChainSummary(
    val status: String = "completed",
    val totalAgents: Int = 0,
    val successCount: Int = 0,
    val results: List<ChainResultItem> = emptyList()
)

/**
 * 单个 Agent/Tool 执行结果条目
 *
 * output 是 Kotlin 对象（通常是 Agent 的 Result data class），
 * 仅在需要喂给 LLM 时才序列化为 JSON。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChainResultItem(
    val agent: String,
    val success: Boolean,
    val output: Any? = null,
    val error: String? = null
)

/**
 * 下一轮调度上下文
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChainContext(
    val initialInput: String,
    val round: Int = 0,
    val results: List<ChainResultItem> = emptyList()
)

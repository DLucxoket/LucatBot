package org.dlucxoket.lucatbot.bot.agent.data

import org.dlucxoket.lucatbot.bot.agent.AgentPlan

/**
 * DispatcherAgent 的请求
 *
 * 模拟真人的决策场景：看到什么信息，决定做什么。
 */
data class DispatcherRequest(
    /** 用户上下文（原始消息、之前的执行结果等） */
    val context: String
)

/**
 * DispatcherAgent 的响应
 *
 * 模拟真人的决策结果：要调用哪些 Agent，或者什么都不做。
 */
data class DispatcherResult(
    /** 是否结束调度（LLM 返回 done） */
    val done: Boolean = false,

    /** 调用计划列表（done 时为空） */
    val plans: List<AgentPlan> = emptyList()
)

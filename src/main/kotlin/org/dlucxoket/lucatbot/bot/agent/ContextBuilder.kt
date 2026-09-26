package org.dlucxoket.lucatbot.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.dlucxoket.lucatbot.bot.agent.data.ChainContext
import org.dlucxoket.lucatbot.bot.agent.data.ChainResultItem
import org.dlucxoket.lucatbot.bot.agent.data.ChainSummary

/**
 * 上下文构建器
 *
 * 单一职责：将执行结果构建为下一轮调度上下文 / 最终汇总。
 * 手拼 JSON → OOP data class + mapper 序列化。
 */
class ContextBuilder(private val mapper: ObjectMapper = ObjectMapper()) {

    /**
     * 构建下一轮调度上下文（OOP → JSON）
     */
    fun buildNextContext(initialInput: String, round: Int, results: List<AgentExecutionResult>): String {
        val context = ChainContext(
            initialInput = initialInput,
            round = round,
            results = results.map { it.toChainResultItem() }
        )
        return mapper.writeValueAsString(context)
    }

    /**
     * 构建最终结果汇总（OOP → JSON）
     */
    fun summarize(results: List<AgentExecutionResult>): String {
        if (results.isEmpty()) {
            return mapper.writeValueAsString(
                ChainSummary(status = "no_action", totalAgents = 0, successCount = 0)
            )
        }

        val summary = ChainSummary(
            status = "completed",
            totalAgents = results.size,
            successCount = results.count { it.success },
            results = results.map { it.toChainResultItem() }
        )
        return mapper.writeValueAsString(summary)
    }

    /**
     * AgentExecutionResult → ChainResultItem（模型转换）
     */
    private fun AgentExecutionResult.toChainResultItem(): ChainResultItem {
        return ChainResultItem(
            agent = agentName,
            success = success,
            output = output,
            error = error
        )
    }
}

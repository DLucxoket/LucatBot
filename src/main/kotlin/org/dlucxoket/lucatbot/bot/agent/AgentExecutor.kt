package org.dlucxoket.lucatbot.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.dlucxoket.lucatbot.bot.agent.data.ChainContext
import org.dlucxoket.lucatbot.bot.agent.data.ChainResultItem
import org.dlucxoket.lucatbot.bot.agent.data.ChainSummary
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Agent/Tool 执行器
 *
 * 单一职责：执行调用计划，收集结果。
 * 不负责发现、解析、上下文构建。
 *
 * **模拟真人行为**：所有 Agent 并行处理（像人一次处理一件事，但系统可并行多路）。
 */
class AgentExecutor(
    private val discovery: AgentDiscovery = AgentDiscovery,
    private val timeoutSeconds: Long = 60
) {
    private val log = org.slf4j.LoggerFactory.getLogger(AgentExecutor::class.java)

    /**
     * 并行执行一组调用计划
     *
     * @param plans 调用计划列表
     * @return 执行结果列表（顺序与 plans 对应）
     */
    fun executeAll(plans: List<AgentPlan>): List<AgentExecutionResult> {
        val futures = plans.map { plan ->
            CompletableFuture.supplyAsync { executeSingle(plan) }
        }

        return try {
            CompletableFuture.allOf(*futures.toTypedArray()).get(timeoutSeconds, TimeUnit.SECONDS)
            futures.map { it.get() }
        } catch (e: Exception) {
            log.error("并行执行超时或异常: ${e.message}")
            futures.mapIndexed { index, future ->
                if (future.isDone && !future.isCompletedExceptionally) {
                    future.get()
                } else {
                    AgentExecutionResult(
                        agentName = plans[index].agentName,
                        input = plans[index].input,
                        output = "",
                        success = false,
                        error = "执行超时或异常: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * 执行单个 Agent/Tool 调用
     */
    fun executeSingle(plan: AgentPlan): AgentExecutionResult {
        return try {
            // 优先 Agent
            val agent = discovery.findAgent(plan.agentName)
            if (agent != null) {
                val output = agent.process(plan.input)
                return AgentExecutionResult(plan.agentName, plan.input, output, true)
            }

            // 再 Tool
            val tool = discovery.findTool(plan.agentName)
            if (tool != null) {
                val output = tool.process(plan.input)
                return AgentExecutionResult(plan.agentName, plan.input, output, true)
            }

            AgentExecutionResult(plan.agentName, plan.input, "", false, "未找到 Agent 或 Tool: ${plan.agentName}")
        } catch (e: Exception) {
            log.error("[${plan.agentName}] 执行异常: ${e.message}")
            AgentExecutionResult(plan.agentName, plan.input, "", false, e.message)
        }
    }
}

package org.dlucxoket.lucatbot.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Agent 调度执行引擎（编排器）
 *
 * **核心设计原则：模拟真人在 QQ 中的真实行为**
 * - 一次只关注一个会话，像真人不可能同时看两个对话
 * - 先看概况再看内容，像人先扫一眼列表再点进去
 * - 决策受性格/心情影响，像人挑会话看和回复都凭感觉
 *
 * 职责（单一：编排调度循环）：
 * 1. 调用 DispatcherAgent 生成调用计划
 * 2. 委托 AgentExecutor 执行计划
 * 3. 委托 ContextBuilder 构建上下文
 * 4. 循环直到 done 或超过最大轮次
 *
 * 发现/执行/上下文构建分别由 [AgentDiscovery]/[AgentExecutor]/[ContextBuilder] 承担。
 *
 * @param dispatcher 调度器实例
 * @param executor   执行器
 * @param contextBuilder 上下文构建器
 * @param maxRounds  最大调度轮次（默认 5）
 */
class AgentChain(
    private val dispatcher: DispatcherAgent = DispatcherAgent,
    private val executor: AgentExecutor = AgentExecutor(),
    private val contextBuilder: ContextBuilder = ContextBuilder(),
    private val maxRounds: Int = 5
) {
    private val log = LoggerFactory.getLogger(AgentChain::class.java)

    /**
     * 执行完整的 Agent 调度链
     *
     * @param initialInput 初始上下文 JSON 字符串
     * @return 最终回复文本（自然语言）
     */
    fun execute(initialInput: String): String {
        val allResults = mutableListOf<AgentExecutionResult>()
        var currentContext = initialInput

        repeat(maxRounds) { round ->
            log.info("=== AgentChain 第 ${round + 1} 轮调度 ===")

            // DispatcherAgent 决策
            val llmOutput = dispatcher.process(currentContext)
            log.info("DispatcherAgent 输出: $llmOutput")

            // 解析调用计划
            val plans = AgentPlanParser.parse(llmOutput)
            if (plans == null || plans.isEmpty()) {
                log.info("调度终止（done 或空计划）")
                return buildUserReply(allResults)
            }

            log.info("解析到 ${plans.size} 个调用计划: ${plans.map { it.agentName }}")

            // 并行执行
            val roundResults = executor.executeAll(plans)
            allResults.addAll(roundResults)

            // 构建下一轮上下文
            currentContext = contextBuilder.buildNextContext(initialInput, round + 1, allResults)
        }

        log.warn("超过最大调度轮次 ($maxRounds)，强制终止")
        return buildUserReply(allResults)
    }

    /**
     * 构建发给用户的自然语言回复
     *
     * 从执行结果中提取最终回复文本，而不是暴露 JSON。
     */
    private fun buildUserReply(results: List<AgentExecutionResult>): String {
        // 找最后一个成功的结果
        val lastSuccess = results.lastOrNull { it.success }
        if (lastSuccess != null) {
            // 尝试从 JSON 结果中提取 reply 字段
            val reply = extractReply(lastSuccess.output)
            if (reply != null) return reply
            return lastSuccess.output
        }

        // 有失败结果
        val lastError = results.lastOrNull { it.error != null }
        if (lastError != null) {
            return "抱歉，处理消息时出了点问题：${lastError.error}"
        }

        // 没有执行任何 Agent
        return ""
    }

    /**
     * 从 JSON 输出中提取 reply 字段
     */
    private fun extractReply(json: String): String? {
        return try {
            val node = ObjectMapper().readTree(json)
            node.get("reply")?.asText()
        } catch (e: Exception) {
            null
        }
    }
}

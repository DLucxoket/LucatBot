package org.dlucxoket.lucatbot.bot.agent

import io.github.classgraph.ClassGraph
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

/**
 * Agent 调度执行引擎
 *
 * 核心设计原则：**模拟真人在 QQ 中的真实行为**
 * - 一次只关注一个会话，像真人不可能同时看两个对话
 * - 先看概况再看内容，像人先扫一眼列表再点进去
 * - 决策受性格/心情影响，像人挑会话看和回复都凭感觉
 *
 * 调度循环（JSON 交互）：
 * 1. 调用 [DispatcherAgent] 生成调用计划（JSON 格式）
 * 2. 解析计划为 [AgentPlan] 列表（或 `done` 终止）
 * 3. 并行执行所有计划中的 Agent/Tool
 * 4. 收集结果，构建 JSON 汇总上下文
 * 5. 将上下文送回 DispatcherAgent 做下一轮决策
 * 6. 循环直到 `done` 或超过最大轮次
 *
 * @param dispatcher 调度器实例
 * @param maxRounds  最大调度轮次，防止无限循环（默认 5）
 */
class AgentChain(
    private val dispatcher: DispatcherAgent = DispatcherAgent,
    private val maxRounds: Int = 5
) {
    private val log = LoggerFactory.getLogger(AgentChain::class.java)
    private val mapper = ObjectMapper()

    /**
     * 执行完整的 Agent 调度链
     *
     * @param initialInput 初始上下文 JSON 字符串
     * @return 最终汇总结果 JSON 字符串
     */
    fun execute(initialInput: String): String {
        val allResults = mutableListOf<AgentExecutionResult>()
        var currentContext = initialInput

        repeat(maxRounds) { round ->
            log.info("=== AgentChain 第 ${round + 1} 轮调度 ===")

            // 第一步：DispatcherAgent 决策
            val llmOutput = dispatcher.process(currentContext)
            log.info("DispatcherAgent 输出: $llmOutput")

            // 第二步：解析调用计划（JSON 格式）
            val plans = AgentPlanParser.parse(llmOutput)
            if (plans == null) {
                log.info("DispatcherAgent 返回 done，链路终止")
                return summarizeResults(allResults)
            }

            if (plans.isEmpty()) {
                log.warn("第 ${round + 1} 轮解析出空计划，终止")
                return summarizeResults(allResults)
            }

            log.info("解析到 ${plans.size} 个调用计划: ${plans.map { it.agentName }}")

            // 第三步：并行执行所有计划
            val roundResults = executeParallel(plans)
            allResults.addAll(roundResults)

            // 第四步：构建下一轮 JSON 上下文
            currentContext = buildNextContext(initialInput, allResults)
        }

        log.warn("超过最大调度轮次 ($maxRounds)，强制终止")
        return summarizeResults(allResults)
    }

    /**
     * 并行执行一组 Agent 调用计划
     */
    private fun executeParallel(plans: List<AgentPlan>): List<AgentExecutionResult> {
        val futures = plans.map { plan ->
            CompletableFuture.supplyAsync {
                executeSingle(plan)
            }
        }

        return try {
            CompletableFuture.allOf(*futures.toTypedArray())
                .get(60, TimeUnit.SECONDS)
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
     *
     * JSON 输入 → Agent 内部 OOP 处理 → JSON 输出
     */
    private fun executeSingle(plan: AgentPlan): AgentExecutionResult {
        return try {
            // 优先按 Agent 查找（Agent.process 处理 JSON 边界）
            val agent = findAgent(plan.agentName)
            if (agent != null) {
                val output = agent.process(plan.input)
                return AgentExecutionResult(plan.agentName, plan.input, output, true)
            }

            // 再按 Tool 查找
            val tool = findTool(plan.agentName)
            if (tool != null) {
                val output = tool.execute(plan.input)
                return AgentExecutionResult(plan.agentName, plan.input, output, true)
            }

            AgentExecutionResult(plan.agentName, plan.input, "", false, "未找到 Agent 或 Tool: ${plan.agentName}")
        } catch (e: Exception) {
            log.error("[${plan.agentName}] 执行异常: ${e.message}")
            AgentExecutionResult(plan.agentName, plan.input, "", false, e.message)
        }
    }

    /**
     * 根据 Agent 类名查找并实例化
     */
    private fun findAgent(agentName: String): Agent<*, *>? {
        val kClass = ClassGraph()
            .enableClassInfo()
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(Agent::class.java.name)
                    .filter { !it.isAbstract && !it.isInterface }
                    .firstOrNull { it.simpleName == agentName }
                    ?.let { it.loadClass().kotlin }
            } ?: return null

        @Suppress("UNCHECKED_CAST")
        return getInstanceOf(kClass as KClass<out Agent<*, *>>)
    }

    /**
     * 根据 Tool 名称查找并实例化
     */
    private fun findTool(toolName: String): Tool? {
        val kClass = ClassGraph()
            .enableClassInfo()
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(Tool::class.java.name)
                    .filter { !it.isAbstract && !it.isInterface }
                    .firstOrNull {
                        val cls = it.loadClass().kotlin
                        val instance = cls.objectInstance ?: cls.createInstance()
                        (instance as? Tool)?.name == toolName || cls.simpleName == toolName
                    }
                    ?.let { it.loadClass().kotlin }
            } ?: return null

        val instance = kClass.objectInstance ?: kClass.createInstance()
        return instance as? Tool
    }

    private fun getInstanceOf(kClass: KClass<out Agent<*, *>>): Agent<*, *> {
        return kClass.objectInstance ?: kClass.createInstance()
    }

    /**
     * 构建下一轮调度上下文（JSON 格式）
     *
     * 模拟真人决策所需的全部信息显式传入：
     * - 初始输入
     * - 已执行的结果摘要
     * - 当前轮次
     */
    private fun buildNextContext(initialInput: String, results: List<AgentExecutionResult>): String {
        val root = mapper.createObjectNode()
        root.put("initialInput", initialInput)
        root.put("round", results.size)

        val resultsArray = mapper.createArrayNode()
        results.forEach { r ->
            val obj = mapper.createObjectNode()
            obj.put("agent", r.agentName)
            obj.put("input", r.input)
            obj.put("success", r.success)
            if (r.success) {
                obj.put("output", r.output)
            } else {
                obj.put("error", r.error ?: "unknown")
            }
            resultsArray.add(obj)
        }
        root.set<ArrayNode>("results", resultsArray)

        return mapper.writeValueAsString(root)
    }

    /**
     * 生成最终结果 JSON
     */
    private fun summarizeResults(results: List<AgentExecutionResult>): String {
        if (results.isEmpty()) {
            return """{"status": "no_action", "message": "没有执行任何 Agent"}"""
        }

        val root = mapper.createObjectNode()
        root.put("status", "completed")
        root.put("totalAgents", results.size)
        root.put("successCount", results.count { it.success })

        val resultsArray = mapper.createArrayNode()
        results.forEach { r ->
            val obj = mapper.createObjectNode()
            obj.put("agent", r.agentName)
            obj.put("success", r.success)
            obj.put("output", r.output)
            if (!r.success) obj.put("error", r.error ?: "")
            resultsArray.add(obj)
        }
        root.set<ArrayNode>("results", resultsArray)

        return mapper.writeValueAsString(root)
    }
}

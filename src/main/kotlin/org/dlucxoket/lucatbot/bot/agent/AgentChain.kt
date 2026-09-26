package org.dlucxoket.lucatbot.bot.agent

import io.github.classgraph.ClassGraph
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

/**
 * Agent 调度执行引擎
 *
 * 核心调度循环：
 * 1. 调用 [DispatcherAgent] 生成调用计划
 * 2. 解析计划为 [AgentPlan] 列表（或 `done` 终止）
 * 3. 并行执行所有计划中的 Agent
 * 4. 收集结果，构建汇总上下文
 * 5. 将汇总上下文送回 DispatcherAgent 做下一轮决策
 * 6. 循环直到 `done` 或超过最大轮次
 *
 * 关键设计约束：
 * - 每轮所有 Agent 并行执行，互不等待
 * - 每个 Agent 的输入由 DispatcherAgent 显式指定，不塞全量上下文
 * - Agent 执行异常不中断链路，记入结果继续
 *
 * @param dispatcher 调度器实例
 * @param maxRounds  最大调度轮次，防止无限循环（默认 5）
 */
class AgentChain(
    private val dispatcher: DispatcherAgent = DispatcherAgent,
    private val maxRounds: Int = 5
) {
    private val log = LoggerFactory.getLogger(AgentChain::class.java)

    /**
     * 执行完整的 Agent 调度链
     *
     * @param initialInput 用户输入的初始上下文
     * @return 最终汇总结果文本
     */
    fun execute(initialInput: String): String {
        val allResults = mutableListOf<AgentExecutionResult>()
        var currentContext = initialInput

        repeat(maxRounds) { round ->
            log.info("=== AgentChain 第 ${round + 1} 轮调度 ===")

            // 第一步：DispatcherAgent 决策
            val llmOutput = dispatcher.process(currentContext)
            log.info("DispatcherAgent 输出: $llmOutput")

            // 第二步：解析调用计划
            val plans = AgentPlanParser.parse(llmOutput)
            if (plans == null) {
                // done → 终止，返回已有结果汇总
                log.info("DispatcherAgent 返回 done，链路终止")
                return summarizeResults(allResults, currentContext)
            }

            if (plans.isEmpty()) {
                log.warn("第 ${round + 1} 轮解析出空计划，终止")
                return summarizeResults(allResults, currentContext)
            }

            log.info("解析到 ${plans.size} 个调用计划: ${plans.map { it.agentName }}")

            // 第三步：并行执行所有计划
            val roundResults = executeParallel(plans)
            allResults.addAll(roundResults)

            // 第四步：构建下一轮上下文（结果汇总）
            currentContext = buildNextContext(initialInput, allResults)
        }

        // 超过最大轮次，强制终止
        log.warn("超过最大调度轮次 ($maxRounds)，强制终止")
        return summarizeResults(allResults, currentContext)
    }

    /**
     * 并行执行一组 Agent 调用计划
     *
     * 每个计划独立执行，单个失败不影响其他。
     *
     * @param plans 调用计划列表
     * @return 执行结果列表（顺序与 plans 对应）
     */
    private fun executeParallel(plans: List<AgentPlan>): List<AgentExecutionResult> {
        val futures = plans.map { plan ->
            CompletableFuture.supplyAsync {
                executeSingle(plan)
            }
        }

        // 等待全部完成，超时兜底
        return try {
            CompletableFuture.allOf(*futures.toTypedArray())
                .get(60, TimeUnit.SECONDS)
            futures.map { it.get() }
        } catch (e: Exception) {
            log.error("并行执行超时或异常: ${e.message}")
            // 超时的返回失败结果
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
     * @param plan 调用计划
     * @return 执行结果（异常时返回失败结果）
     */
    private fun executeSingle(plan: AgentPlan): AgentExecutionResult {
        return try {
            // 优先按 Agent 查找
            val agent = findAgent(plan.agentName)
            if (agent != null) {
                val output = agent.process(plan.input)
                return AgentExecutionResult(
                    agentName = plan.agentName,
                    input = plan.input,
                    output = output,
                    success = true
                )
            }

            // 再按 Tool 查找
            val tool = findTool(plan.agentName)
            if (tool != null) {
                val output = tool.execute(plan.input)
                return AgentExecutionResult(
                    agentName = plan.agentName,
                    input = plan.input,
                    output = output,
                    success = true
                )
            }

            AgentExecutionResult(
                agentName = plan.agentName,
                input = plan.input,
                output = "",
                success = false,
                error = "未找到 Agent 或 Tool: ${plan.agentName}"
            )
        } catch (e: Exception) {
            log.error("[${plan.agentName}] 执行异常: ${e.message}")
            AgentExecutionResult(
                agentName = plan.agentName,
                input = plan.input,
                output = "",
                success = false,
                error = e.message
            )
        }
    }

    /**
     * 根据 Agent 类名查找并实例化
     *
     * 通过 ClassGraph 扫描 classpath，匹配简单类名。
     *
     * @param agentName Agent 的简单类名
     * @return Agent 实例，未找到返回 null
     */
    private fun findAgent(agentName: String): Agent? {
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
        return getInstanceOf(kClass as KClass<out Agent>)
    }

    /**
     * 根据 Tool 名称查找并实例化
     *
     * @param toolName Tool 的 name 属性或简单类名
     * @return Tool 实例，未找到返回 null
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

    /**
     * 获取 Agent 实例（优先 object 单例，否则创建新实例）
     */
    private fun getInstanceOf(kClass: KClass<out Agent>): Agent {
        return kClass.objectInstance ?: kClass.createInstance()
    }

    /**
     * 构建下一轮调度上下文
     *
     * 格式：
     * ```
     * 原始输入：
     * xxx
     *
     * 已执行结果：
     * [AgentName] 输入 → 输出
     * [AgentName] 输入 → 输出
     * ```
     *
     * @param originalInput 初始用户输入
     * @param results       所有已执行的结果
     * @return 下一轮调度上下文
     */
    private fun buildNextContext(originalInput: String, results: List<AgentExecutionResult>): String {
        val sb = StringBuilder()
        sb.appendLine("原始输入：")
        sb.appendLine(originalInput)
        sb.appendLine()
        sb.appendLine("已执行结果：")
        results.forEach { r ->
            if (r.success) {
                sb.appendLine("[${r.agentName}] ${r.input} → ${r.output}")
            } else {
                sb.appendLine("[${r.agentName}] ${r.input} → 执行失败: ${r.error}")
            }
        }
        return sb.toString().trim()
    }

    /**
     * 生成最终结果汇总
     *
     * @param results 所有执行结果
     * @param context 最终上下文
     * @return 最终回复文本
     */
    private fun summarizeResults(results: List<AgentExecutionResult>, context: String): String {
        if (results.isEmpty()) {
            return context
        }

        // 取最后一个成功的结果作为最终回复
        val lastSuccess = results.lastOrNull { it.success }
        return lastSuccess?.output ?: results.last().let {
            if (it.success) it.output else "执行出错: ${it.error}"
        }
    }
}

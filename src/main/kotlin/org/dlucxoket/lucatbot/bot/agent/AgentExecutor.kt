package org.dlucxoket.lucatbot.bot.agent

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent/Tool 执行器
 *
 * 单一职责：执行调用计划，收集结果。
 * 不负责发现、解析、上下文构建。
 *
 * **OOP 约定**：计划与结果均为 Kotlin 对象（[AgentPlan] / [AgentExecutionResult]），
 * 链内不传递 JSON 字符串。
 *
 * **并发约定**：计划执行是阻塞的 LLM HTTP 调用（timeout 120s），
 * 不能跑在公共 ForkJoinPool 上（会占满 commonPool 拖垮 JVM 其他并行流），
 * 统一提交到 [sharedPool]（daemon 线程，`lucatbot-agent-` 前缀）。
 */
class AgentExecutor(
    private val discovery: AgentDiscovery = AgentDiscovery,
    private val timeoutSeconds: Long = 60
) {
    private val log = org.slf4j.LoggerFactory.getLogger(AgentExecutor::class.java)

    companion object {
        private const val THREAD_PREFIX = "lucatbot-agent-"

        private val threadCounter = AtomicInteger()

        /**
         * 所有 [AgentExecutor] 实例共享的线程池。
         *
         * 选 cached 而非固定池：并行 plan 数通常只有 1~5 且是突发的，
         * 每个任务长时间阻塞——cached 按需建线程、空闲 60s 回收，
         * 轮次之间不占线程，也无需猜固定池大小；
         * 任务量被 plan 数天然限制，不会无界膨胀。
         * 线程为 daemon：即使未显式 shutdown，JVM 退出也不会被卡住。
         */
        private val sharedPool: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "$THREAD_PREFIX${threadCounter.incrementAndGet()}").apply { isDaemon = true }
        }

        init {
            // 兜底：非 Spring 场景（或未走 @PreDestroy）时随 JVM 退出回收线程池
            Runtime.getRuntime().addShutdownHook(
                Thread({ sharedPool.shutdown() }, "$THREAD_PREFIX.shutdown-hook")
            )
        }
    }

    /**
     * 并行执行一组调用计划
     *
     * @param plans 调用计划列表
     * @return 执行结果列表（顺序与 plans 对应）
     */
    fun executeAll(plans: List<AgentPlan>): List<AgentExecutionResult> {
        val futures = plans.map { plan ->
            CompletableFuture.supplyAsync({ executeSingle(plan) }, sharedPool)
        }

        return try {
            CompletableFuture.allOf(*futures.toTypedArray()).get(timeoutSeconds, TimeUnit.SECONDS)
            futures.map { it.get() }
        } catch (e: Exception) {
            log.error("并行执行超时或异常: ${e.message}")
            // 尽力取消未完成的任务，避免池中线程继续空等。
            // 注意：cancel(true) 对阻塞中的 HTTP 调用不保证中断生效，仅 best-effort。
            futures.forEach { future -> if (!future.isDone) future.cancel(true) }
            futures.mapIndexed { index, future ->
                if (future.isDone && !future.isCompletedExceptionally) {
                    future.get()
                } else {
                    AgentExecutionResult(
                        agentName = plans[index].agentName,
                        input = plans[index].input,
                        output = null,
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
                val output = agent.processObject(plan.input)
                return AgentExecutionResult(plan.agentName, plan.input, output, true)
            }

            // 再 Tool
            val tool = discovery.findTool(plan.agentName)
            if (tool != null) {
                val output = tool.processObject(plan.input)
                return AgentExecutionResult(plan.agentName, plan.input, output, true)
            }

            AgentExecutionResult(plan.agentName, plan.input, null, false, "未找到 Agent 或 Tool: ${plan.agentName}")
        } catch (e: Exception) {
            log.error("[${plan.agentName}] 执行异常: ${e.message}")
            AgentExecutionResult(plan.agentName, plan.input, null, false, e.message)
        }
    }

    /**
     * 关闭共享线程池（幂等）。
     *
     * 线程池是 companion 级共享的，关闭影响所有实例；
     * 由 [AgentChain] 的 `@PreDestroy` 在 Spring 关闭时调用，
     * companion 另有 JVM shutdown hook 兜底。
     */
    fun shutdown() {
        sharedPool.shutdown()
    }
}

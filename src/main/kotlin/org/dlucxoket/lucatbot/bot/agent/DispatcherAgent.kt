package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.DispatcherRequest
import org.dlucxoket.lucatbot.bot.agent.data.DispatcherResult
import org.dlucxoket.lucatbot.bot.tool.Tool
import kotlin.reflect.full.createInstance

/**
 * 智能体调度器（DispatcherAgent）
 *
 * **模拟真人的决策过程**：像人看一眼消息列表，决定点进哪个会话。
 *
 * 核心职责：根据当前上下文，自动决定接下来应调用哪些 Agent，并生成调用计划。
 *
 * JSON 边界：`{"context": "..."}` ↔ `DispatcherRequest`
 * OOP 内部：`DispatcherRequest` → `DispatcherResult`（包含调用计划或 done 状态）
 *
 * 类发现委托给 [AgentDiscovery]（带缓存）。
 * 注意：这是一个 Kotlin `object`（单例）。
 */
object DispatcherAgent : Agent<DispatcherRequest, DispatcherResult>() {
    /**
     * 调度器的功能描述
     */
    override val description: String = """
        一个Agent调度器，决定接下来调用哪些Agent来处理用户消息。
        输出JSON格式的调用计划：
        [{"agent": "AgentName", "input": {...}}]

        **重要规则**：
        - 收到用户消息时，调用一个Agent来处理（通常是ReplyAgent）
        - 如果已有Agent成功回复了用户，直接返回done，不要再调用
        - 不要重复调用同一个Agent处理同一件事
        - input由你显式指定，只传该Agent需要的信息
    """.trimIndent()

    override val inputClass: Class<DispatcherRequest> = DispatcherRequest::class.java

    /**
     * 原始上下文 → DispatcherRequest（不走 JSON 反序列化，直接包装）
     */
    override fun fromJson(json: String): DispatcherRequest {
        return DispatcherRequest(context = json)
    }

    override fun handle(input: DispatcherRequest): DispatcherResult {
        return processDispatcher(input)
    }

    /**
     * 根据上下文，决策下一步应调用哪些 Agent/Tool
     */
    private fun processDispatcher(request: DispatcherRequest): DispatcherResult {
        val sb = StringBuilder()

        // Agent 列表（从缓存获取）
        sb.appendLine("可用的Agent：")
        AgentDiscovery.getAgentClasses().forEach { kClass ->
            val instance = kClass.objectInstance ?: kClass.createInstance()
            sb.appendLine("- ${kClass.simpleName}: ${instance.shortDescription}")
        }

        // Tool 列表（从缓存获取）
        sb.appendLine()
        sb.appendLine("可用的Tool（数据查询工具）：")
        AgentDiscovery.getToolClasses().forEach { kClass ->
            val instance = kClass.objectInstance ?: kClass.createInstance()
            sb.appendLine("- ${instance.name}: ${instance.description}  参数：${instance.parameterHint}")
        }

        // LLM 决策
        val llmOutput = llm.ask("""
            $systemPromptHead

            $sb

            上下文：
            ${request.context}

            请输出JSON格式的调用计划。规则：
            1. 如果还没有Agent回复过用户，调用ReplyAgent回复
            2. 如果已经有Agent成功回复了用户，输出done
            3. 不要重复调用同一个Agent处理同一件事

            示例：
            [{"agent": "ReplyAgent", "input": {"context": "用户的消息", "messages": []}}]
            或者输出 done。
        """.trimIndent())

        // 解析 LLM 输出为 OOP 结果
        val plans = AgentPlanParser.parse(llmOutput)
        return if (plans == null) {
            DispatcherResult(done = true)
        } else {
            DispatcherResult(done = false, plans = plans)
        }
    }
}

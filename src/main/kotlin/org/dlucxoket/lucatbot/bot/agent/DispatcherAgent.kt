package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.tool.Tool
import kotlin.reflect.full.createInstance

/**
 * 智能体调度器（DispatcherAgent）
 *
 * **模拟真人的决策过程**：像人看一眼消息列表，决定点进哪个会话。
 *
 * 核心职责：根据当前上下文，自动决定接下来应调用哪些 Agent/Tool，并生成调用计划。
 *
 * LLM 输出格式（JSON）：
 * ```json
 * [{"agent": "AgentName", "input": {...}}, ...]
 * ```
 * 或 `"done"` 表示终止。
 *
 * 输入/输出均为 JSON 字符串（LLM API 边界）。
 * 类发现委托给 [AgentDiscovery]（带缓存）。
 *
 * 注意：这是一个 Kotlin `object`（单例）。
 */
object DispatcherAgent : Agent<String, String>() {
    /**
     * 调度器的功能描述
     */
    override val description: String = """
        一个Agent调度器，模拟真人决定接下来做什么。
        根据上下文决定调用哪些Agent，输出JSON格式的调用计划：
        [{"agent": "AgentName", "input": {...}}]
        或者返回"done"表示不需要再调用任何Agent。
        
        约束：
        - input由你显式指定，只传该Agent需要的信息
        - 不需要就返回done
    """.trimIndent()

    override val inputClass: Class<String> = String::class.java

    override fun handle(input: String): String {
        return processDispatcher(input)
    }

    /**
     * 根据上下文，决策下一步应调用哪些 Agent/Tool
     *
     * @param input 用户输入的上下文 JSON
     * @return LLM 生成的调用计划 JSON
     */
    private fun processDispatcher(input: String): String {
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
        return llm.ask("""
            $systemPromptHead

            $sb

            上下文：
            $input

            请输出JSON格式的调用计划，例如：
            [{"agent": "ReplyAgent", "input": {"context": "...", "messages": [...]}}]
            或者直接输出 done 表示不需要再调用。
        """.trimIndent())
    }
}

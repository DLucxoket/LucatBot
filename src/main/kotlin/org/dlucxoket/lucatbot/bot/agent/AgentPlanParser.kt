package org.dlucxoket.lucatbot.bot.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode

/**
 * DispatcherAgent 输出解析器
 *
 * 将 LLM 返回的调用计划解析为 [AgentPlan] 列表。
 *
 * 输出格式（JSON）：
 * ```json
 * [
 *   {"agent": "EchoAgent", "input": {"text": "hello"}},
 *   {"agent": "ReplyAgent", "input": {"context": "..."}}
 * ]
 * ```
 * 或 `"done"` 表示终止。
 */
object AgentPlanParser {

    private val mapper = ObjectMapper()

    /**
     * 解析 LLM 输出的调用计划
     *
     * @param llmOutput DispatcherAgent 返回的 JSON 文本
     * @return 调用计划列表；若输出为 `done` 则返回 null
     */
    fun parse(llmOutput: String): List<AgentPlan>? {
        val trimmed = llmOutput.trim()

        // done → 终止
        if (trimmed.equals("done", ignoreCase = true) || trimmed == "\"done\"") {
            return null
        }

        return try {
            val node: JsonNode = mapper.readTree(trimmed)

            when {
                node.isArray -> parseJsonArray(node as ArrayNode)
                node.isObject -> listOfNotNull(toAgentPlan(node))
                node.isTextual && node.asText().equals("done", true) -> null
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 JSON 数组中的调用计划
     */
    private fun parseJsonArray(array: ArrayNode): List<AgentPlan>? {
        val plans = array.mapNotNull { element ->
            if (element.isObject) toAgentPlan(element) else null
        }
        return plans.ifEmpty { null }
    }

    /**
     * 从 JSON 对象中提取 AgentPlan
     *
     * 格式：{"agent": "AgentName", "input": {...}}
     */
    private fun toAgentPlan(obj: JsonNode): AgentPlan? {
        val agentName = obj.get("agent")?.asText()?.trim() ?: return null
        if (agentName.isEmpty()) return null

        val inputNode = obj.get("input")
        val input = when {
            inputNode == null || inputNode.isNull -> "{}"
            inputNode.isTextual -> inputNode.asText()
            else -> mapper.writeValueAsString(inputNode)
        }

        return AgentPlan(agentName, input)
    }

    /**
     * 将 AgentPlan 列表序列化为 JSON 字符串
     */
    fun toJson(plans: List<AgentPlan>): String {
        return mapper.writeValueAsString(plans)
    }
}

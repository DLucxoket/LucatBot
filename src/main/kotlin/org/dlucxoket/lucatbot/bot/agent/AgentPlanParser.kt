package org.dlucxoket.lucatbot.bot.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory

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

    private val log = LoggerFactory.getLogger(AgentPlanParser::class.java)
    private val mapper = jacksonObjectMapper()

    /**
     * 解析 LLM 输出的调用计划
     *
     * @param llmOutput DispatcherAgent 返回的 JSON 文本
     * @return 调用计划列表；若输出为 `done` 则返回 null
     */
    fun parse(llmOutput: String, depth: Int = 0): List<AgentPlan>? {
        if (depth > 3) return null

        var trimmed = llmOutput.trim()

        // 剥掉 markdown 代码块标记（LLM 常用 ```json ... ``` 包裹）
        trimmed = trimmed.removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        // done → 终止
        if (trimmed.equals("done", ignoreCase = true) || trimmed == "\"done\"") {
            return null
        }

        // 剥掉外层引号（LLM 有时把 JSON 包在字符串里返回）
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
        }

        return try {
            val node: JsonNode = mapper.readTree(trimmed)

            when {
                node.isArray -> {
                    @Suppress("UNCHECKED_CAST")
                    parseJsonArray(node as ArrayNode)
                }
                node.isObject -> {
                    // 支持 {"done": true/false, "plans": [...]} 格式
                    if (node.has("done")) {
                        if (node.get("done").asBoolean()) return null
                        val plans = node.get("plans")
                        if (plans != null && plans.isArray) {
                            parseJsonArray(plans as ArrayNode)
                        } else {
                            null
                        }
                    } else {
                        listOfNotNull(toAgentPlan(node))
                    }
                }
                node.isTextual -> {
                    val inner = node.asText().trim()
                    if (inner.equals("done", true)) null
                    else parse(inner, depth + 1)
                }
                else -> null
            }
        } catch (e: Exception) {
            log.warn("AgentPlanParser 解析失败: {}", e.message)
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
     * input 在此转换为 Kotlin Map，链内传递不再使用 JSON 字符串。
     */
    private fun toAgentPlan(obj: JsonNode): AgentPlan? {
        val agentName = obj.get("agent")?.asText()?.trim() ?: return null
        if (agentName.isEmpty()) return null

        return AgentPlan(agentName, toInputMap(obj.get("input")))
    }

    /**
     * JsonNode → Kotlin Map（OOP 对象）
     */
    @Suppress("UNCHECKED_CAST")
    private fun toInputMap(inputNode: JsonNode?): Map<String, Any?> {
        if (inputNode == null || inputNode.isNull) return emptyMap()

        // 对象节点：直接转 Map
        if (inputNode.isObject) {
            return mapper.convertValue(inputNode, Map::class.java) as Map<String, Any?>
        }

        // 文本节点：LLM 有时把 JSON 包在字符串里
        if (inputNode.isTextual) {
            val text = inputNode.asText().trim()
            if (text.isEmpty()) return emptyMap()
            return try {
                val parsed = mapper.readTree(text)
                if (parsed.isObject) {
                    mapper.convertValue(parsed, Map::class.java) as Map<String, Any?>
                } else {
                    mapOf("text" to text)
                }
            } catch (e: Exception) {
                mapOf("text" to text)
            }
        }

        // 标量/数组：包一层
        return mapOf("value" to mapper.convertValue(inputNode, Any::class.java))
    }
}

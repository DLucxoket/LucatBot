package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * LLM 输出的 JSON 提取/解析辅助
 *
 * LLM 返回常裹 markdown 代码块或前后缀说明文字，
 * 解析前先剥出最外层 JSON 对象，避免边界反序列化被噪音干扰。
 */
object LlmJson {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    /** 从 LLM 原文提取 JSON 对象字符串（失败返回原文 trim） */
    fun extract(raw: String): String {
        val trimmed = raw.trim()
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(trimmed)
        val body = fenced?.groupValues?.get(1)?.trim() ?: trimmed
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        return if (start >= 0 && end > start) body.substring(start, end + 1) else body
    }

    /** 解析为 JsonNode；失败返回 null */
    fun parseTree(raw: String): JsonNode? {
        return try {
            mapper.readTree(extract(raw))
        } catch (e: Exception) {
            null
        }
    }

    /** 把信封里的 params 节点转成 Map；缺失/非对象返回空 Map */
    fun paramsOf(node: JsonNode): Map<String, Any?> {
        val paramsNode = node.get("params") ?: return emptyMap()
        if (!paramsNode.isObject) return emptyMap()
        return mapper.convertValue(paramsNode, Map::class.java) as Map<String, Any?>
    }
}

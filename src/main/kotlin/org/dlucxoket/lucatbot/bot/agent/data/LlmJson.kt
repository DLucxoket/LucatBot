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
    fun parseTree(raw: String): JsonNode? = parseLenient(extract(raw))

    /** 只读一次（失败 null） */
    private fun tryRead(text: String): JsonNode? = try {
        mapper.readTree(text)
    } catch (e: Exception) {
        null
    }

    /** 常见手误：冒号后多写一个 =（实盘 14:35 的 `"reason":="…"`）——清洗后再解析 */
    private val STRAY_EQ = Regex(""":\s*=""")

    /**
     * 逐级容错解析：原文 → 清洗手误（[STRAY_EQ]）→ 补缺尾闭合（[repairJson]）→ 仍失败 null
     *
     * 模型偶发的手误（多一个 =、尾巴写丢）不值得让整轮决策作废兜底退出。
     */
    fun parseLenient(text: String): JsonNode? {
        val cleaned = STRAY_EQ.replace(text, ":")
        return tryRead(text) ?: tryRead(cleaned) ?: tryRead(repairJson(cleaned))
    }

    /**
     * 截断修复：补上缺失的结尾闭合符（`}` / `]`，字符串没闭合先补引号）
     *
     * 实盘出过：模型把 JSON 尾巴写丢（`{"content": [{...}]` 少了 `]}`），
     * 整轮输出被判「非 JSON」直接兜底退出——内容其实是好的，补个尾就能用。
     * 只**追加**闭合符，不改动已有内容；本来就是好的原文原样返回。
     */
    fun repairJson(text: String): String {
        val closers = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (ch in text) {
            when {
                escaped -> escaped = false
                inString && ch == '\\' -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> closers.addLast('}')
                ch == '[' -> closers.addLast(']')
                ch == '}' || ch == ']' -> if (closers.isNotEmpty()) closers.removeLast()
            }
        }
        if (closers.isEmpty() && !inString) return text
        val quote = if (inString) "\"" else ""
        return text + quote + closers.reversed().joinToString("")
    }

    // —— tool-call 形态容错：模型偶发把输出包成「函数调用」标记（invoke/function
    //    + parameter 标签）——那不是本协议（纯 JSON 信封），这里把它翻译回信封。
    //    字面标记在代码里拼接（避免注释/文档里出现可被误识别的连续标记）。
    private const val LT = "<"
    private val INVOKE_OPEN = Regex("""$LT(?:invoke\s+name=|function=)"?([A-Za-z_][\w-]*)""")
    private val INVOKE_PARAM = Regex(
        """${LT}parameter\s*(?:name\s*=\s*)?=?\s*"?(params|reason)"?\s*>([\s\S]*?)${LT}/parameter>"""
    )

    /**
     * tool-call 形态的原文 → 纯 JSON 信封（`{"action":…,"params":{…},"reason":…}`）
     *
     * @return 翻译后的信封 JSON；**不是该形态 → null**（调用方照常走原解析）
     */
    fun toolCallEnvelope(raw: String): String? {
        val action = INVOKE_OPEN.find(raw)?.groupValues?.get(1)?.lowercase() ?: return null
        val params = INVOKE_PARAM.findAll(raw).associate { m ->
            m.groupValues[1] to m.groupValues[2].trim()
        }
        val node = mapper.createObjectNode()
        node.put("action", action)
        // params 里的 JSON 可能缺尾/带手误 → 容错解析；坏了/不是对象就用空 params
        params["params"]?.let { p ->
            val json = parseLenient(p)
            if (json != null && json.isObject) node.set<JsonNode>("params", json)
        }
        params["reason"]?.takeIf { it.isNotBlank() }?.let { node.put("reason", it) }
        return mapper.writeValueAsString(node)
    }

    /** 把信封里的 params 节点转成 Map；缺失/非对象返回空 Map */
    fun paramsOf(node: JsonNode): Map<String, Any?> {
        val paramsNode = node.get("params") ?: return emptyMap()
        if (!paramsNode.isObject) return emptyMap()
        return mapper.convertValue(paramsNode, Map::class.java) as Map<String, Any?>
    }
}

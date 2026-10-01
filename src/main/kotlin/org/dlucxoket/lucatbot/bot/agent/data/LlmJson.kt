package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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

    // —— tool-call 形态容错：模型偶发把输出包成「函数调用」标记——不是本协议
    //    （纯 JSON 信封），这里翻译回信封：JSON 解析不了时就走这条路径。
    //    支持整体 params JSON（老）与逐个参数标签（新，含裸标签对）两种形态。
    //    字面标记在代码里拼接（避免注释里出现可被误识别的连续标记）。
    private const val LT = "<"
    private val INVOKE_OPEN = Regex("""$LT(?:invoke\s+name=|function=)"?([A-Za-z_][\w-]*)""")
    private val PARAM_TAG = Regex(
        """${LT}parameter\s*(?:name\s*=\s*)?=?\s*["']?([A-Za-z_][\w-]*)["']?\s*>([\s\S]*?)$LT/parameter>"""
    )

    /** 极简裸参数对（如 `<messageId>123</messageId>`；结构标签除外） */
    private val BARE_TAG = Regex("""$LT([A-Za-z_][\w-]*)>([^$LT]*)$LT/\1>""")
    private val STRUCT_TAGS = setOf("function", "invoke", "parameter", "output", "content")

    /** 能当 action 的名字——**不认识的 function 名一律按「工具调用」处理** */
    private val KNOWN_ACTIONS = setOf(
        "read_more", "readmore", "send", "stay", "return",
        "list_tools", "tools", "tool", "call_tool",
        "view_images", "view", "view_image",
        "open_conversation", "idle", "memory_queries", "search_memory"
    )

    /** 可从参数标签提到信封**顶层**的字段（与 JSON 信封的顶层字段一致） */
    private val TOP_LEVEL_FIELDS = listOf("memory_queries", "remember", "todo_add", "todo_done")

    /**
     * tool-call 形态的原文 → 纯 JSON 信封（`{"action":…,"params":{…},"reason":…}`）
     *
     * @return 翻译后的信封 JSON；**不是该形态 → null**（调用方照常走原解析）
     */
    fun toolCallEnvelope(raw: String): String? {
        val called = INVOKE_OPEN.find(raw)?.groupValues?.get(1) ?: return null
        val args = LinkedHashMap<String, String>()
        PARAM_TAG.findAll(raw).forEach { args.putIfAbsent(it.groupValues[1], it.groupValues[2].trim()) }
        BARE_TAG.findAll(raw).forEach { m ->
            val key = m.groupValues[1]
            if (key !in STRUCT_TAGS) args.putIfAbsent(key, m.groupValues[2].trim())
        }
        val jsonParams = args["params"]?.let { p -> parseLenient(p)?.takeIf { it.isObject } }
        val flat = argsToNode(args)
        val node = mapper.createObjectNode()
        val action = called.lowercase()
        if (action in KNOWN_ACTIONS) {
            node.put("action", action)
            node.set<JsonNode>("params", when {
                jsonParams != null -> jsonParams
                action == "tool" || action == "call_tool" -> {
                    val obj = mapper.createObjectNode()
                    obj.set<JsonNode>("name", flat.get("name") ?: mapper.getNodeFactory().textNode(called))
                    obj.set<JsonNode>("input", flat.deepCopy().also { it.remove("name") })
                    obj
                }
                else -> flat
            })
            // 顶层字段随行（写在参数标签里也认：remember/todo_*/memory_queries）
            TOP_LEVEL_FIELDS.forEach { key ->
                flat.get(key)?.let { node.set<JsonNode>(key, it) }
            }
        } else {
            // 不认识的名字 = 工具调用（<function=GetMsgTool><messageId>…）
            node.put("action", "tool")
            val obj = mapper.createObjectNode()
            obj.put("name", called)
            obj.set<JsonNode>("input", jsonParams ?: flat)
            node.set<JsonNode>("params", obj)
        }
        args["reason"]?.takeIf { it.isNotBlank() }?.let { node.put("reason", it) }
        return mapper.writeValueAsString(node)
    }

    /** 逐个参数标签 → JSON 对象（值先按 JSON 读、失败按字符串；跳过 params/reason） */
    private fun argsToNode(args: Map<String, String>): ObjectNode {
        val obj = mapper.createObjectNode()
        args.forEach { (k, v) ->
            if (k == "params" || k == "reason" || v.isEmpty()) return@forEach
            obj.set<JsonNode>(k, parseLenient(v) ?: mapper.getNodeFactory().textNode(v))
        }
        return obj
    }

    /** 把信封里的 params 节点转成 Map；缺失/非对象返回空 Map */
    fun paramsOf(node: JsonNode): Map<String, Any?> {
        val paramsNode = node.get("params") ?: return emptyMap()
        if (!paramsNode.isObject) return emptyMap()
        return mapper.convertValue(paramsNode, Map::class.java) as Map<String, Any?>
    }

    /** JSON 对象字符串 → Map（容错解析；不是对象 → null）——input 被双层编码时用 */
    fun mapOrNull(json: String): Map<String, Any?>? =
        parseLenient(json)?.takeIf { it.isObject }
            ?.let { mapper.convertValue(it, Map::class.java) as Map<String, Any?> }

    /**
     * tool 调用入参的容错提取（两个决策智能体共用）
     *
     * `input` 是对象 → 直用；是字符串（被双层编码）→ 解析；
     * **缺失/形态不对 → 用 params 里除 name 外的字段平铺兜底**（模型常把
     * `type/target` 直接写在 params 顶层而不包 input——实盘 SwitchConversationTool
     * 连调两次报错就是这么来的）
     */
    @Suppress("UNCHECKED_CAST")
    fun toolInputOf(params: Map<String, Any?>): Map<String, Any?> {
        when (val raw = params["input"]) {
            is Map<*, *> -> return raw as Map<String, Any?>
            is String -> mapOrNull(raw)?.let { return it }
        }
        return params.filterKeys { !it.equals("name", ignoreCase = true) }
    }
}

package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 记忆整理请求（退出会话时由 ConversationController 组装）
 *
 * 两个阶段共用本类型，按 [candidates] 是否为空区分：
 * - **阶段1（复盘）**：conversationContent / existingMemory / timeHint 有值，
 *   产出候选记忆（含 [MemoryReflectItem.keywords]）
 * - **阶段2（写入前关键词复核）**：[candidates] + [matchedMemories] 有值，
 *   用关键词查库命中的已有记忆复核候选，过滤改写式重复后再写入
 *
 * @param conversationContent 本次会话内容（多行文本，来自聊天记录摘要）
 * @param existingMemory 该对象的现有记忆摘要（读取参考，用于去重/避免矛盾；可空串）
 * @param timeHint 当前时间提示（可选）
 * @param pendingTodos 阶段1：未完成待办清单（`- [ ] (id前4位) 内容` 多行；
 *   空 = 没有待办，此时不提示待办结算）——本轮 LLM 顺带判断哪些**已经办完**，
 *   输出到结果的 [MemoryReflectResult.doneTodos]
 * @param candidates 二阶段：待复核的候选记忆（非空 = 复核模式）
 * @param matchedMemories 二阶段：用候选关键词查库命中的已有记忆正文
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MemoryReflectRequest(
    @JsonProperty("conversationContent")
    val conversationContent: String = "",

    @JsonProperty("existingMemory")
    val existingMemory: String = "",

    @JsonProperty("timeHint")
    val timeHint: String = "",

    @JsonProperty("pendingTodos")
    val pendingTodos: String = "",

    @JsonProperty("candidates")
    val candidates: List<MemoryReflectItem> = emptyList(),

    @JsonProperty("matchedMemories")
    val matchedMemories: List<String> = emptyList()
)

/**
 * 一条待写入的记忆
 *
 * @param kind fact=客观事实、opinion=主观看法、event=发生过的事/约定
 * @param content 一句话描述
 * @param tags 检索标签（可选）
 * @param keywords 检索关键词——**尽量写全**（核心名词/专名 + 同义词、别名、
 *   口语说法、相关词，建议 5~10 个），写入前拿它查库判重、覆盖面越广命中越准；
 *   阶段1 必给；解析缺失时编排层退化用 content 查
 */
data class MemoryReflectItem(
    val kind: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val keywords: List<String> = emptyList()
)

/**
 * 记忆整理结果（LLM 判断出的、值得长期记住的内容）
 *
 * 空列表 + [degraded]=false 表示「复核后确实没有要记的」；
 * [degraded]=true 表示解析/LLM 失败的兜底结果（内容不可信）——
 * 阶段2 复核失败时据此**保守放行**候选，绝不因复核环节故障吞掉记忆。
 *
 * @param doneTodos 阶段1 顺带产出的**待办结算**：本次对话确实办完的待办引用
 *   （id / id 前 4 位 / 内容片段；空 = 没办完的）——编排层据此标完成 + 留痕，
 *   不依赖 LLM 在会话里自觉输出 todo_done
 */
data class MemoryReflectResult(
    val memories: List<MemoryReflectItem> = emptyList(),
    val doneTodos: List<String> = emptyList(),
    val degraded: Boolean = false
) {
    companion object {
        /** 解析失败/LLM 异常的兜底结果（degraded=true，内容不可信） */
        val DEGRADED = MemoryReflectResult(degraded = true)

        /** 合法的空结果（LLM 明确输出没有要记的） */
        val EMPTY = MemoryReflectResult()

        /**
         * 从 LLM 输出解析记忆列表 + 待办结算
         *
         * 期望纯 JSON：`{"memories": [...], "done_todos": ["引用"]}`。
         * memories 解析失败/缺失/非数组 → [DEGRADED]；content 空白的条目丢弃。
         * **done_todos 只要有合法数组就取**——即使 memories 段坏了也不连带丢掉
         * 结算结论（LLM 明确给出的 done_todos 是可信的）。
         */
        fun parse(llmOutput: String): MemoryReflectResult {
            val node = LlmJson.parseTree(llmOutput) ?: return DEGRADED
            val doneTodos = node.stringList("done_todos")
            val array = node.get("memories")?.takeIf { it.isArray }
                ?: return MemoryReflectResult(doneTodos = doneTodos, degraded = true)
            val items = array.mapNotNull { entry ->
                if (!entry.isObject) return@mapNotNull null
                val content = entry.get("content")
                    ?.takeIf { !it.isNull }
                    ?.asText()?.trim()
                    .orEmpty()
                if (content.isEmpty()) return@mapNotNull null
                val kind = entry.get("kind")
                    ?.takeIf { !it.isNull }
                    ?.asText()?.trim()
                    .orEmpty()
                val tags = entry.stringList("tags")
                val keywords = entry.stringList("keywords")
                MemoryReflectItem(kind = kind, content = content, tags = tags, keywords = keywords)
            }
            return MemoryReflectResult(items, doneTodos)
        }

        /** 读取字符串数组字段（空缺/非数组/含 null → 空列表） */
        private fun com.fasterxml.jackson.databind.JsonNode.stringList(field: String): List<String> {
            val node = get(field) ?: return emptyList()
            if (!node.isArray) return emptyList()
            return node.filter { !it.isNull }
                .map { it.asText().trim() }
                .filter { it.isNotEmpty() }
        }
    }
}

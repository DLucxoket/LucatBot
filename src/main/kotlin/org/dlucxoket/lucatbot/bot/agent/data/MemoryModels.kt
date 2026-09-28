package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 记忆整理请求（退出会话时由 ConversationController 组装）
 *
 * @param conversationContent 本次会话内容（多行文本，来自聊天记录摘要）
 * @param existingMemory 该对象的现有记忆摘要（读取参考，用于去重/避免矛盾；可空串）
 * @param timeHint 当前时间提示（可选）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MemoryReflectRequest(
    @JsonProperty("conversationContent")
    val conversationContent: String = "",

    @JsonProperty("existingMemory")
    val existingMemory: String = "",

    @JsonProperty("timeHint")
    val timeHint: String = ""
)

/**
 * 一条待写入的记忆
 *
 * @param kind fact=客观事实、opinion=主观看法、event=发生过的事/约定
 * @param content 一句话描述
 * @param tags 检索标签（可选）
 */
data class MemoryReflectItem(
    val kind: String = "",
    val content: String = "",
    val tags: List<String> = emptyList()
)

/**
 * 记忆整理结果（LLM 判断出的、值得长期记住的内容）
 *
 * 空列表表示本次对话没有值得记录的东西。
 */
data class MemoryReflectResult(
    val memories: List<MemoryReflectItem> = emptyList()
) {
    companion object {
        /** 解析失败/无内容的兜底空结果 */
        val EMPTY = MemoryReflectResult()

        /**
         * 从 LLM 输出解析记忆列表
         *
         * 期望纯 JSON：`{"memories": [{"kind": ..., "content": ..., "tags": [...]}]}`。
         * 解析失败、memories 缺失或非数组 → [EMPTY]；content 空白的条目丢弃。
         */
        fun parse(llmOutput: String): MemoryReflectResult {
            val node = LlmJson.parseTree(llmOutput) ?: return EMPTY
            val array = node.get("memories")?.takeIf { it.isArray } ?: return EMPTY
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
                val tagsNode = entry.get("tags")
                val tags = if (tagsNode != null && tagsNode.isArray) {
                    tagsNode.filter { !it.isNull }
                        .map { it.asText().trim() }
                        .filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
                MemoryReflectItem(kind = kind, content = content, tags = tags)
            }
            return MemoryReflectResult(items)
        }
    }
}

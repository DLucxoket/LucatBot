package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 进场记忆预搜的请求（ConversationController 组装：当前聊天记录窗口）
 *
 * 与 [MemoryReflectRequest] 的分工：那个是**退出会话时复盘要不要记**，
 * 这个是**进场时拆该搜什么**——一个往里写，一个往外查。
 *
 * @param conversationContent 当前聊天记录窗口（多行纯文本，含新消息注释）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MemoryKeywordRequest(
    @JsonProperty("conversationContent")
    val conversationContent: String = ""
)

/**
 * 关键词拆分结果——直接拿去 [org.dlucxoket.lucatbot.bot.memory.MemoryService.search] 查库
 *
 * 每个词**独立可搜**（单个词/短语，不是整句）；空列表 = 这段聊天没有值得回忆的词
 * （寒暄、表情、无信息量），编排层照常进场、只是这次不预搜。
 */
data class MemoryKeywordResult(
    @JsonProperty("keywords")
    val keywords: List<String> = emptyList()
) {
    companion object {
        /** 一次最多交出的词数（编排层每词最多取 3 条命中，上限防结果段被撑爆） */
        const val MAX_KEYWORDS = 12

        /**
         * 从 LLM 输出解析关键词；**任何异常/形态不符一律空结果**
         *
         * 预搜是锦上添花——拆词挂了就当没记忆照常进场，绝不阻断第一轮决策
         * （与 [MemoryReflectResult] 的 degraded 兜底同哲学，只是这里不值得标记）。
         */
        fun parse(llmOutput: String): MemoryKeywordResult {
            val node = LlmJson.parseTree(llmOutput) ?: return MemoryKeywordResult()
            val array = node.get("keywords")?.takeIf { it.isArray } ?: return MemoryKeywordResult()
            return MemoryKeywordResult(
                array.filter { !it.isNull }
                    .map { it.asText().trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(MAX_KEYWORDS)
            )
        }
    }
}

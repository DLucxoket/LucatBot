package org.dlucxoket.lucatbot.bot.llm

/**
 * 多轮上下文里一轮消息的**角色**
 *
 * 对齐 OpenAI 协议的 `role` 字段：`system` / `user` / `assistant`。
 * 刻意不引入 tool/function 之类的角色——本项目 Agent 全走固定脚本内的
 * 决策 JSON，不需要协议级工具调用。
 */
enum class LlmRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant")
}

/**
 * 多轮上下文里的一轮消息（OOP 边界对象）
 *
 * **JSON 只在 LLM API 边界**：调用方（Agent 等）用它拼好历史，
 * [OpenAIAPI] 在边界处转成 langchain4j 的 `ChatMessage`。
 * data class + val，不可变优先。
 *
 * @param role    角色（system/user/assistant）
 * @param content 消息正文
 */
data class LlmTurn(val role: LlmRole, val content: String)

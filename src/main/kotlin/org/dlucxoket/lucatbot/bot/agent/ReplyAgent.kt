package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.ReplyRequest
import org.dlucxoket.lucatbot.bot.agent.data.ReplyResult

/**
 * 智能回复 Agent
 *
 * **模拟真人的对话行为**：
 * - 像真人一样理解消息内容再回复，而不是机械应答
 * - 说话自然、口语化，像在 QQ 聊天
 * - 会根据上下文判断该不该回、怎么回
 *
 * JSON 边界：`{"context": "...", "messages": [...], ...}` ↔ `ReplyRequest`
 * OOP 内部：`ReplyRequest` → `ReplyResult`
 */
object ReplyAgent : Agent<ReplyRequest, ReplyResult>() {
    override val description: String = """
        智能回复Agent，模拟真人的聊天方式，根据上下文生成自然的对话回复。
        适合用于回应用户的闲聊、提问或一般性对话。
        输出应该简洁、口语化、符合QQ聊天场景，像一个真实的人在打字。
    """.trimIndent()

    override val inputClass: Class<ReplyRequest> = ReplyRequest::class.java

    /**
     * OOP 处理：接收 [ReplyRequest]，调用 LLM 生成回复，返回 [ReplyResult]
     */
    override fun handle(input: ReplyRequest): ReplyResult {
        val systemPrompt = """$systemPromptHead

你是一个友善的QQ聊天机器人，正在模拟真实用户的聊天行为。
要求：
- 说话自然、简洁、口语化，像真人打字
- 不要过于正式，不要像客服
- 根据上下文判断该说什么
- 可以用语气词（哈哈、嗯嗯、啊这）让对话更自然
- 性格：${input.personality}
- 当前心情：${input.mood}
"""
        // 构造 LLM 输入（消息列表摘要）
        val messageText = input.messages.joinToString("\n") { "${it.sender}: ${it.content}" }
        val question = """上下文：${input.context}

消息记录：
$messageText

请生成一条自然的回复。"""

        val replyText = llm.ask(systemPrompt, question)

        return ReplyResult(
            reply = replyText,
            action = "send",
            confidence = 1.0
        )
    }
}

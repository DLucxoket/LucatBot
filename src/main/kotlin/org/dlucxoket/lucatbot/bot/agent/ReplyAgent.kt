package org.dlucxoket.lucatbot.bot.agent

/**
 * 智能回复 Agent
 *
 * 调用 LLM 根据上下文生成自然的对话回复。
 * 这是机器人对外表现"智能"的核心 Agent。
 */
object ReplyAgent : Agent() {
    override val description: String = """
        智能回复Agent，根据上下文生成自然、友善的对话回复。
        适合用于回应用户的闲聊、提问或一般性对话。
        输出应该简洁、口语化、符合聊天场景。
    """.trimIndent()

    /**
     * 生成智能回复
     *
     * @param input 上下文信息（包含用户消息和之前的结果）
     * @return LLM 生成的回复
     */
    override fun process(input: String): String {
        val systemPrompt = """$systemPromptHead

你是一个友善的QQ聊天机器人，说话自然、简洁、口语化。
根据上下文生成合适的回复，不要过于正式。
"""
        return llm.ask(systemPrompt, input)
    }
}

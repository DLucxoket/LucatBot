package org.dlucxoket.lucatbot.bot.agent

/**
 * 智能回复 Agent
 *
 * **模拟真人的对话行为**：
 * - 像真人一样理解消息内容再回复，而不是机械应答
 * - 说话自然、口语化，像在 QQ 聊天
 * - 会根据上下文判断该不该回、怎么回
 *
 * 输入 JSON：`{"context": "...", "messages": [...]}` 或纯文本场景的 `{"text": "..."}`
 * 输出 JSON：`{"reply": "回复内容", "action": "send"}`
 */
object ReplyAgent : Agent() {
    override val description: String = """
        智能回复Agent，模拟真人的聊天方式，根据上下文生成自然的对话回复。
        适合用于回应用户的闲聊、提问或一般性对话。
        输出应该简洁、口语化、符合QQ聊天场景，像一个真实的人在打字。
    """.trimIndent()

    /**
     * 模拟真人生成智能回复
     *
     * @param input JSON 字符串，包含上下文和消息内容
     * @return JSON 字符串，包含回复内容和动作类型
     */
    override fun process(input: String): String {
        val systemPrompt = """$systemPromptHead

你是一个友善的QQ聊天机器人，正在模拟真实用户的聊天行为。
要求：
- 说话自然、简洁、口语化，像真人打字
- 不要过于正式，不要像客服
- 根据上下文判断该说什么
- 可以用语气词（哈哈、嗯嗯、啊这）让对话更自然

请根据输入的JSON数据生成回复。
"""
        val reply = llm.ask(systemPrompt, input)

        return """{"reply": "$reply", "action": "send"}"""
    }
}

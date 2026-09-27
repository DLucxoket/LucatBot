package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.ReplyRequest
import org.dlucxoket.lucatbot.bot.agent.data.ReplyResult
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness

/**
 * 智能回复 Agent
 *
 * **模拟真人的对话行为**：
 * - 像真人一样理解消息内容再回复，而不是机械应答
 * - 说话自然、口语化，像在 QQ 聊天
 * - 会根据上下文判断该不该回、怎么回
 * - 考虑心情和时间（深夜回得简短/冷淡）
 * - 不一定每次都回（模拟真人有时候已读不回）
 *
 * JSON 边界：`ReplyRequest` ↔ `ReplyResult`
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
        val mood = BotMood.guessByTime()

        // 根据心情决定是否回复（模拟真人有时候不回）
        val replyProbability = mood.replyWillingness()
        if (Math.random() > replyProbability) {
            return ReplyResult(reply = "", action = "ignore", confidence = replyProbability)
        }

        // 根据心情调整说话风格
        val styleHint = when (mood.type) {
            BotMood.MoodType.HAPPY -> "心情不错，可以多用emoji，语气活泼一点，回复可以稍长"
            BotMood.MoodType.NORMAL -> "正常聊天，自然就好"
            BotMood.MoodType.BORED -> "有点无聊，语气平淡，回复简短"
            BotMood.MoodType.TIRED -> "有点累，回复尽量简短，语气慵懒，可以用...结尾"
            BotMood.MoodType.ANNOYED -> "心情不好，语气冷淡，能不回就不回"
            BotMood.MoodType.SLEEPY -> "很困了，回复极其简短，甚至只回一个表情或'嗯'"
        }

        val systemPrompt = """$systemPromptHead

你是一个叫「${input.personality}」的QQ用户，正在模拟真实用户的聊天行为。
要求：
- 说话自然、简洁、口语化，像真人打字
- 不要过于正式，不要像客服
- 根据上下文判断该说什么
- 可以用语气词（哈哈、嗯嗯、啊这）让对话更自然
- 当前心情：${mood.toPrompt()}——$styleHint
- ${if (TimeAwareness.isLateNight()) "现在是深夜，回得简短一点" else ""}

请根据输入的数据生成一条自然的回复。
"""

        val messageText = input.messages.joinToString("\n") { "${it.sender}: ${it.content}" }
        val question = """上下文：${input.context}

聊天记录：
$messageText

请生成一条自然的回复。"""

        val replyText = llm.ask(systemPrompt, question)

        return ReplyResult(reply = replyText, action = "send", confidence = replyProbability)
    }
}

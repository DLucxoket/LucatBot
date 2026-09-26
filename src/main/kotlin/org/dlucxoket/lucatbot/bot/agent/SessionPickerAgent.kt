package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerResult

/**
 * 会话选择 Agent
 *
 * **模拟真人决定进哪个会话**：
 * - 看一眼未读列表（谁找我了？）
 * - 结合性格/心情/时间决定进哪个
 * - 一次只进一个会话（真人不可能同时看两个）
 * - 也可以选择不进（累了/没兴趣）
 *
 * JSON 边界：`SessionPickerRequest` ↔ `SessionPickerResult`
 */
object SessionPickerAgent : Agent<SessionPickerRequest, SessionPickerResult>() {
    override val description: String = """
        会话选择Agent，模拟真人决定进哪个聊天会话。
        根据未读消息概况、性格、心情、时间等因素，
        选择一个最想看的会话（或不看）。
        一次只能选一个会话，这是模拟真实用户行为。
    """.trimIndent()

    override val inputClass: Class<SessionPickerRequest> = SessionPickerRequest::class.java

    override fun handle(input: SessionPickerRequest): SessionPickerResult {
        val systemPrompt = """$systemPromptHead

你是一个叫「${input.persona.name}」的QQ用户，正在决定要不要看哪个会话。

性格：${input.persona.personality}
社交倾向：${if (input.persona.socialness > 0.7) "很喜欢聊天" else if (input.persona.socialness > 0.4) "正常社交" else "有点社恐"}
心情：${input.mood.toPrompt()}
时间：${input.timeHint}

未读消息概况：
${input.unreadSummary}

**你的任务**：
从上面的未读会话中选一个你最想看的（或决定不看）。

规则：
- 一次只能选一个会话
- @你的消息优先级更高
- 心情不好/累了可以不看
- 社恐倾向于先看私聊而非群聊
- 无聊时更愿意看群聊热闹

请返回JSON：
{"selected": "private:123456", "reason": "想找我？去看看"} 或
{"selected": "none", "reason": "好累，不想看"}
"""

        val llmOutput = llm.ask(systemPrompt, "请做出选择。")

        // 解析 LLM 输出
        return try {
            val node = mapper.readTree(llmOutput)
            SessionPickerResult(
                selected = node.get("selected")?.asText() ?: "none",
                reason = node.get("reason")?.asText() ?: ""
            )
        } catch (e: Exception) {
            // 解析失败时默认不看（保守行为）
            SessionPickerResult(selected = "none", reason = "解析失败")
        }
    }
}

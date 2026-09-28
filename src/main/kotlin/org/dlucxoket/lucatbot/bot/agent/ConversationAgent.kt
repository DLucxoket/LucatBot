package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.ConversationDecision
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.llm.LLM

/**
 * 会话内决策智能体
 *
 * 进入会话后不立刻回复：先看历史、记忆、操作日志，
 * 决定爬楼 / 发消息 / 返回。只输出动作（统一信封），
 * IO 一律由 Tool 执行（发送工具 / 聊天记录工具）。
 *
 * 解析失败兜底 [ConversationDecision.FALLBACK]（RETURN），避免卡死在会话里。
 */
class ConversationAgent(
    llm: LLM = LLM.DEFAULT
) : Agent<ConversationRequest, ConversationDecision>(llm) {
    override val description: String = """
        会话内决策智能体，决定在聊天会话里接下来做什么：
        爬楼看历史、发一条消息、或者退出会话。
        只输出动作，不直接执行。
    """.trimIndent()

    override val inputClass: Class<ConversationRequest> = ConversationRequest::class.java

    override fun handle(input: ConversationRequest): ConversationDecision {
        val systemPrompt = buildPrompt(input)
        val llmOutput = llm.ask(systemPrompt, "请做出选择。")
        return ConversationDecision.parse(llmOutput)
    }

    private fun buildPrompt(input: ConversationRequest): String {
        val historyText = input.recentMessages.joinToString("\n") { line ->
            val who = if (line.isMe) "我" else "${line.sender}(${line.senderId})"
            "- $who [${line.time}]：${line.content}"
        }
        val newMsgText = if (input.newMessageNotes.isEmpty()) {
            ""
        } else {
            "\n" + input.newMessageNotes.joinToString("\n")
        }
        val proactiveHint = if (input.proactive) {
            "\n（这是你主动发起的会话，请先发一条简短的破冰招呼，例如「在吗」「干嘛呢」）"
        } else {
            ""
        }
        val extraHint = if (input.hint.isNotBlank()) "\n${input.hint}" else ""

        return """你正在使用即时聊天工具，已经点进了一个会话。
当前时间（北京时间）：${input.timeHint}
## 上下文
### 你的信息
${input.persona.toPrompt()}
### 当前状态
- 心情：${input.mood.toPrompt()}
- 会话类型：${input.conversationType}
- 会话名称：${input.conversationName}(${input.conversationId})
### 短期记忆
${input.shortTermMemory.ifBlank { "（暂无）" }}
### 你最近的操作
${input.actionLog}
### 最近聊天记录
${historyText.ifBlank { "（新会话）" }}$newMsgText

## 你的选择（严格遵循）
只输出纯JSON，结构如下：

{
"action": "read_more" | "send" | "return",
"params": { ... },
"reason": "..."
}

- read_more：再看看前面的历史。params：{"count": 10}（可选）
- send：发一条消息。params：{"content": "正文"}，交给发送工具
- return：退出本会话，回到消息列表。params 可省略
- reason 是内心动机（不会发出去）

约束：
- 一次只做一个动作
- content 应口语、简短、像真人打字，不要像客服
- 不想回或累了可以 return，或者 send 很短的敷衍$proactiveHint$extraHint
"""
    }
}

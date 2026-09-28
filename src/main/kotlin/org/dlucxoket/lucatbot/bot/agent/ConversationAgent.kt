package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.ConversationDecision
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.llm.LLM

/**
 * 会话内决策智能体
 *
 * 进入会话后不立刻回复：先看历史、记忆、操作日志，
 * 决定爬楼 / 发消息 / 停留一会儿 / 返回。只输出动作（统一信封），
 * IO 一律由 Tool 执行（发送工具 / 聊天记录工具）。
 *
 * 解析失败兜底 [ConversationDecision.FALLBACK]（RETURN），避免卡死在会话里。
 */
class ConversationAgent(
    llm: LLM = LLM.DEFAULT
) : Agent<ConversationRequest, ConversationDecision>(llm) {
    override val description: String = """
        会话内决策智能体，决定在聊天会话里接下来做什么：
        爬楼看历史、发一条消息、停留一会儿、或者退出会话。
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
        val crawlStatus = buildString {
            appendLine("### 爬楼状态")
            appendLine("- 已爬楼 ${input.readMoreRounds} 次，累计覆盖最近 ${input.historyCovered} 条消息")
            if (input.reachedHistoryStart) {
                appendLine("- 已翻到最早消息，没有更早的历史了")
            }
            appendLine("- 参考聊天记录里每条消息的时间与顶部当前时间，判断消息新旧与停留时机")
        }

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
### 待办
${input.todos.ifBlank { "（无待办）" }}
### 你最近的操作
${input.actionLog}
$crawlStatus### 最近聊天记录
${historyText.ifBlank { "（新会话）" }}$newMsgText

## 你的选择（严格遵循）
只输出纯JSON，结构如下：

{
"action": "read_more" | "send" | "stay" | "return",
"params": { ... },
"reason": "..."
}

- read_more：再看看前面的历史。params：{"count": 10}（可选）
- send：发一条消息。params：{"content": "正文"}，交给发送工具
- stay：在当前会话停留一会儿再重新决策。params：{"seconds": 30}（通常 10~120 秒；停留期间新消息会被记录，结束后你会看到）
- return：退出本会话，回到消息列表。params 可省略
- reason 是内心动机（不会发出去）

约束：
- 一次只做一个动作
- content 应口语、简短、像真人打字，不要像客服
- 不要习惯性在句末加 emoji 或笑脸，绝大多数消息就是纯文字
- 发完消息后你会立即再次决策：没有新消息时不要连续发送，通常用 stay 等待回应或 return
- 结合「爬楼状态」决定去留：看够了就 send 或 return；已翻到最早消息时不要再 read_more，也不要无休止地反复爬楼
- 用户提出了需要你长期记住的事（不是闲聊）→ 在 JSON 顶层额外输出 "remember": "值得记住的一句话"
- 用户托付了一件现在不做、之后要做的事 → 在 JSON 顶层额外输出 "todo_add": "待办内容"
- 本次对话中完成了待办清单里的事 → 在 JSON 顶层额外输出 "todo_done": "待办的id或内容"
- remember / todo_add / todo_done 都是可选的，可以省略；普通闲聊不要往里写
- 不想回或累了可以 return，或者 send 很短的敷衍$proactiveHint$extraHint
"""
    }
}

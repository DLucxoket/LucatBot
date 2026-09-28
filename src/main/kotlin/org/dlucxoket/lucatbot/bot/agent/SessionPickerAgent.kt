package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickParser
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerRequest
import org.dlucxoket.lucatbot.bot.llm.LLM

/**
 * 查看会话智能体（消息列表决策）
 *
 * 模拟真人扫 QQ 消息列表：结合性格/心情/未读/操作日志，
 * 决定打开哪一个会话（最多一个），或去空闲。
 *
 * LLM 输出统一决策信封 `{"action","params","reason"}`，
 * OOP 输出 [SessionPick]；action=idle 时返回 null。
 */
class SessionPickerAgent(
    llm: LLM = LLM.DEFAULT
) : Agent<SessionPickerRequest, SessionPick?>(llm) {
    override val description: String = """
        查看会话智能体，模拟真人扫一眼QQ消息列表后决定进哪个会话。
        一次最多打开一个会话，也可以选择什么都不看去空闲。
    """.trimIndent()

    override val inputClass: Class<SessionPickerRequest> = SessionPickerRequest::class.java

    override fun handle(input: SessionPickerRequest): SessionPick? {
        val systemPrompt = buildPrompt(input)
        val llmOutput = llm.ask(systemPrompt, "请做出选择。")
        return SessionPickParser.parse(llmOutput)
    }

    private fun buildPrompt(input: SessionPickerRequest): String {
        val sessionsText = if (input.sessions.isEmpty()) {
            "（没有未读）"
        } else {
            input.sessions.joinToString("\n") { s ->
                val typeLabel = s.conversationType
                val unread = if (s.unreadCount > 0) "(${s.unreadCount}条未读)" else ""
                val at = when {
                    s.isAtMe -> "(@我)"
                    s.isAtAll -> "(@全体)"
                    else -> ""
                }
                "- $typeLabel ${s.name}(${s.id})(${s.time})$unread$at：${s.lastMessage}"
            }
        }

        val proactiveText = if (input.proactiveCandidates.isEmpty()) {
            ""
        } else {
            val block = input.proactiveCandidates.joinToString("\n") {
                "- 私聊 ${it.name}(${it.id})：${it.hint}"
            }
            "\n### 可主动找的人（仅在空闲唤醒时出现）\n$block\n"
        }

        return """你是一个正在使用即时聊天工具的用户，现在你需要选择其中一个会话进行查看
当前时间（北京时间）：${input.timeHint}
## 上下文
### 你的信息
${input.persona.toPrompt()}
### 当前状态
- 心情：${input.mood.toPrompt()}
### 短期记忆
${input.shortTermMemory.ifBlank { "（暂无）" }}
### 待办
${input.todos.ifBlank { "（无待办）" }}
### 你最近的操作
${input.actionLog}
### 未读消息
$sessionsText$proactiveText
## 输出格式（严格遵循）
只输出纯JSON，结构如下：

{
"action": "open_conversation" | "idle",
"params": { ... },
"reason": "..."
}

- open_conversation：打开一个会话。params：{"type": "private/group/proactive", "target": "ID"}
- idle：不看任何会话，去空闲。params 可省略
- reason 是内心动机（不会发给用户）

proactive 仅限「可主动找的人」里列出的；没有合适对象请 idle
"""
    }
}

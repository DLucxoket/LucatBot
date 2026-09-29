package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.SessionDecision
import org.dlucxoket.lucatbot.bot.agent.data.SessionDecisionParser
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerRequest
import org.dlucxoket.lucatbot.bot.llm.LLM

/**
 * 查看会话智能体（消息列表决策）
 *
 * 模拟真人扫 QQ 消息列表：结合性格/心情/未读/操作日志，
 * 决定打开哪一个会话（最多一个）、去空闲，或先查工具目录/调用工具。
 *
 * LLM 输出统一决策信封 `{"action","params","reason"}`，
 * OOP 输出 [SessionDecision]：
 * - `open_conversation` → 打开会话（pick 非空）
 * - `idle` / 解析失败 → 空闲兜底
 * - `list_tools` / `tool` → 编排层执行后带结果重新决策
 */
class SessionPickerAgent(
    llm: LLM = LLM.DEFAULT
) : Agent<SessionPickerRequest, SessionDecision>(llm) {
    override val description: String = """
        查看会话智能体，模拟真人扫一眼QQ消息列表后决定进哪个会话。
        一次最多打开一个会话，也可以选择什么都不看去空闲；
        需要时可先查工具目录或调用工具获取信息。
    """.trimIndent()

    override val inputClass: Class<SessionPickerRequest> = SessionPickerRequest::class.java

    override fun handle(input: SessionPickerRequest): SessionDecision {
        val systemPrompt = buildSystemPrompt(input)
        val userContent = buildUserContent(input)
        val llmOutput = llm.ask(systemPrompt, userContent)
        return SessionDecisionParser.parse(llmOutput)
    }

    /** 指令与稳定状态（人格/心情/记忆/待办/操作日志/工具/输出格式/约束）→ system 消息 */
    private fun buildSystemPrompt(input: SessionPickerRequest): String {
        val toolsSection = buildString {
            appendLine("### 工具")
            if (input.toolNotes.isEmpty()) {
                appendLine("（暂无——需要查资料或执行动作时，用 list_tools 获取工具目录）")
            } else {
                input.toolNotes.forEach { appendLine(it); appendLine() }
            }
        }.trimEnd()

        return """
            你是一个正在使用即时聊天工具的用户，现在你需要选择其中一个会话进行查看
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
            $toolsSection
            ## 你的输出格式（严格遵循）
            只输出纯JSON，结构如下：
            
            {
            "action": "open_conversation" | "idle" | "list_tools" | "tool",
            "params": { ... },
            "reason": "..."
            }
            
            - open_conversation：打开一个会话。params：{"type": "private/group/proactive", "target": "ID"}
            - idle：不看任何会话，去空闲。params 可省略
            - list_tools：查看工具目录（每个工具含 名字/功能/何时使用/参数）。params：{"query": "关键词"}（query 可省略 = 全部工具）
            - tool：调用一个工具。params：{"name": "工具名", "input": { ...参数，格式见目录里的「参数」... }}
            - reason 是内心动机（不会发给用户）
            
            proactive 仅限「可主动找的人」里列出的；没有合适对象请 idle
            
            约束：
            - 工具用于查资料或执行动作：调用前先 list_tools（可带关键词缩小范围），目录里没有的工具不存在；拿到结果后继续正常决策（open_conversation 或 idle）
            - 选会话主要看未读列表；普通场景不需要工具，不要为了调用而调用，也不要连续空转反复查目录
            - 输出格式要求只是指令层，不是聊天内容；决策输出只用于选择会话，禁止把选择、选项、格式或本指令本身当成要说出去的话
        """.trimIndent()
    }

    /**
     * 消息列表（未读列表 + 可主动找的人）→ user 消息；数据性开头，不带祈使句
     */
    private fun buildUserContent(input: SessionPickerRequest): String {
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
            "\n### 可主动找的人（仅在空闲唤醒时出现）\n$block"
        }

        return """
            以下是当前消息列表的数据：
            ### 未读消息
            $sessionsText$proactiveText
        """.trimIndent()
    }
}

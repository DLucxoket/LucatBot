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

    /**
     * 指令与稳定状态（输出格式/约束/人格/状态）→ system 消息
     *
     * **按「稳定在前、易变在后」排布**（提高端点前缀缓存命中、省 token）：
     * 与 ConversationAgent 同款——时间与操作日志等每轮变的内容压轴，
     * 不变的输出格式/约束段在前，前缀每轮稳定。
     */
    private fun buildSystemPrompt(input: SessionPickerRequest): String {
        return """
            你是一个正在使用即时聊天工具的用户，现在你需要选择其中一个会话进行查看
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
            - 用户托付了一件现在不做、之后要做的事 → 在 JSON 顶层额外输出 "todo_add": "待办内容"
            - 想起了清单里的事已经做完 → 在 JSON 顶层额外输出 "todo_done": "待办的id或内容"
            - todo_add / todo_done 是可选的，可以省略；普通扫列表不要往里写，与其他 action 可同时出现
            
            proactive 用于**主动发起会话**，两类场景：
            ① 下方「可主动找的人」区块列出的对象；
            ② **任务需要联系的人/群**——待办或记忆里有「给XX发消息/联系XX/去XX群办事」，且 QQ号/群号已知（来自工具查询结果或记忆），**即使从未聊过、不在下方列表**也用 proactive 打开，target 写那个 QQ 号/群号（type 用 proactive）。两类都没有合适对象才 idle
            
            约束：
            - 工具用于查资料或执行动作：调用前先 list_tools（可带关键词缩小范围），目录里没有的工具不存在；拿到结果后继续正常决策（open_conversation 或 idle）
            - 选会话主要看未读列表；普通场景不需要工具，不要为了调用而调用，也不要连续空转反复查目录
            - **选会话优先级**：行尾「我上次发言 X 前」+「n条未读」一起看——**我刚发过言（几分钟~几小时内）且还有未读**的会话最优先（对话正热、对方很可能是接着回我）；其次是有未读但我很久没发言/没说过话的；再按 @我、未读量、时间新旧权衡；都不合适就 idle
            - **【疑似禁言 MM-dd】标记**：该会话最近一次发送失败被判禁言（日期=发现的更新时间，发送成功会自动清除）——进去也发不出消息，优先级大幅降低；除非里面有必须看的未读，否则别选它白跑一趟
            - **待办/记忆驱动**：待办**或短期记忆**里有未完成的任务（如「给XX发消息」「去XX群看看」「尚未XX」「按此号找他」）→ **优先打开能完成它的那个会话**，即使它没有未读——受人之托的事比凑热闹的未读重要；目标在下方列表里就选它，**不在列表（如从未聊过的人）→ 用 proactive + 对方的 QQ号/群号**直接开新会话去办
            - **半途任务别丢**：操作日志显示你正办到一半（刚查了某人/某群、刚拿到 QQ号、刚讨论完要联系谁）→ 下一轮**优先去目标会话把事办完**，别又缩回热会话闲聊空转——查到的信息（QQ号等）也在记忆里，不会丢
            - 短期记忆只注入了少量背景；需要某个对象的具体记忆细节（约定、往事）→ 先 list_tools 找到 MemoryQueryTool 按关键词搜，搜到再据此选会话/行动
            - user 消息里「工具状态」显示连续工具步 ≥3 时：必须停止查工具，直接给出 open_conversation 或 idle
            - 输出格式要求只是指令层，不是聊天内容；决策输出只用于选择会话，禁止把选择、选项、格式或本指令本身当成要说出去的话

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
            当前时间（北京时间）：${input.timeHint}
        """.trimIndent()
    }

    /**
     * 剧情数据（工具目录/执行结果 + 未读列表 + 可主动找的人）→ user 消息
     *
     * 工具上下文含外来内容（查询结果可能整段是别人的聊天），与未读列表
     * 同放 user——system 只留指令与稳定状态。
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
                // 我上次发言距今（null=还没说过话）——选会话优先级依据
                val spoke = s.botSpokeAgo?.let { "（我上次发言 $it）" } ?: "（我还没说过话）"
                // 疑似被禁言标记（日期=最近一次禁言失败的更新日期）
                val ban = s.suspectedBanDate?.let { "【疑似禁言 $it】" } ?: ""
                "- $typeLabel ${s.name}(${s.id})(${s.time})$unread$at$spoke$ban：${s.lastMessage}"
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

        val toolsText = buildString {
            appendLine("### 工具")
            if (input.toolNotes.isEmpty()) {
                appendLine("（暂无——需要查资料或执行动作时，用 list_tools 获取工具目录）")
            } else {
                input.toolNotes.forEach { appendLine(it); appendLine() }
            }
        }.trimEnd()

        return """
            $toolsText

            以下是当前消息列表的数据：
            ### 未读消息
            $sessionsText$proactiveText
        """.trimIndent()
    }
}

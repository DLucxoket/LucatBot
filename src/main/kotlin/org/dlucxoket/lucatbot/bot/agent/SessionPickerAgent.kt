package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.SessionDecision
import org.dlucxoket.lucatbot.bot.agent.data.SessionDecisionParser
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionAction
import org.dlucxoket.lucatbot.bot.agent.data.SessionSummaryLine
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.dlucxoket.lucatbot.bot.llm.TokenUsageAggregator
import org.dlucxoket.lucatbot.bot.llm.fixIndent
import org.dlucxoket.lucatbot.bot.llm.inMemoryTokenUsage

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
    llm: LLM = LLM.DEFAULT,
    /** token 账本（构造注入，见 [Agent]）——生产由 LoopConfig 注入容器里同一个 bean */
    tokenUsage: TokenUsageAggregator = inMemoryTokenUsage()
) : Agent<SessionPickerRequest, SessionDecision>(llm, tokenUsage) {
    override val description: String = """
        查看会话智能体，模拟真人扫一眼QQ消息列表后决定进哪个会话。
        一次最多打开一个会话，也可以选择什么都不看去空闲；
        需要时可先查工具目录或调用工具获取信息。
    """.trimIndent()

    override val inputClass: Class<SessionPickerRequest> = SessionPickerRequest::class.java

    /** token 统计：这一问得出的动作（open_conversation / idle / list_tools…） */
    override fun usageAction(input: SessionPickerRequest, output: SessionDecision): String? =
        output.action.wire

    /** token 统计的会话维度：选会话固定发生在「消息列表」上下文 */
    override fun conversationTag(input: SessionPickerRequest): String = "消息列表"

    override fun handle(input: SessionPickerRequest): SessionDecision {
        val systemPrompt = buildSystemPrompt(input)
        val userContent = buildUserContent(input)
        val llmOutput = llm.ask(systemPrompt, userContent)
        return SessionDecisionParser.parse(llmOutput)
    }

    /**
     * 静态指令段（**不含任何入参数据**）——native 上下文模式合并进共享首轮 system
     *
     * 压缩模式下 [buildSystemPrompt] = 本段 + "\n\n" + 动态状态段（逐字等同拆分前
     * 的整份模板，默认路径一个字节不变）；native 模式本段只在首轮 system 发一次，
     * 每轮 ask 的 systemPrompt 由 SharedLlmContext 按精确前缀匹配剥除。
     */
    override val staticInstructions: String by lazy {
        """
            你是一个正在使用即时聊天工具的用户，现在你需要选择其中一个会话进行查看
            ## 你的输出格式（严格遵循）
            - **只输出纯 JSON**：不要用「函数调用 / XML 标签」包裹（invoke、function、parameter），也不要裹解释文字——标签形态只是系统的容错兜底

            {
            "action": ${SessionAction.promptList()},
            "params": { ... },
            "reason": "..."
            }

            - open_conversation：打开一个会话。params：{"type": "private/group/proactive", "target": "ID"}
            - idle：不看任何会话，去空闲。params：{"seconds": 空闲秒数}——**睡多久由你决定**（按心情、时间、有没有事判断；600~7200 秒 = 10 分钟~2 小时，超范围钳到边界）：心情差/犯困、短期没人找 → 3600~7200；心情好、可能很快有人回话 → 600~1800。省略 params = 按心情随机兜底（能判断就自己给）
            - list_tools：查看工具目录（每个工具含 名字/功能/何时使用/参数）。params：{"query": "关键词"}（query 可省略 = 全部工具）——**工具名不常驻 prompt，要用工具有多陌生都先 list_tools**
            - tool：调用一个工具。params：{"name": "工具名", "input": { ...参数，格式见目录里的「参数」... }}
            - reason 是内心动机（不会发给用户）
            - "todo_add"（之后要做的事）/ "todo_done"（清单里的事做完了）是可选的 JSON 顶层字段，普通扫列表不要往里写，可与其他 action 同时出现；要标什么时候做就同给 `"todo_add_time": "yyyy-MM-dd HH:mm"`（预计执行，可省）

            proactive 用于**主动发起会话**，两类场景：
            ① 下方「可主动找的人」区块列出的对象；
            ② **任务需要联系的人/群**——待办或记忆里有「给XX发消息/联系XX/去XX群办事」，且 QQ号/群号已知 → **即使从未聊过、不在下方列表**也用 proactive 打开，target 写那个号（type 用 proactive）。两类都没有合适对象才 idle

            约束：
            - 工具：先 list_tools（可带关键词），目录里没有的工具不存在；拿到结果继续正常决策（open_conversation 或 idle）；普通场景不需要工具，别为调用而调用、别空转反复查目录
            - **选会话优先级**：行尾「我上次发言 X 前」+「n条未读」一起看——**我刚发过言且还有未读**的会话最优先（对话正热）；其次是有未读但我很久没发言/没说过话的；再按 @我、未读量、时间新旧权衡；都不合适就 idle
            - **刚退出的会话别马上再进**（user 消息里「刚退出（冷却中）」段列出的）：刚出来往往还没什么新内容可接，立刻又进就是原地打转——**除非有 @你/@全体/回复你 或特别要紧的未读，否则等它从冷却段里消失（约 2 分钟）再进**
            - **【疑似禁言 MM-dd】标记**：发送失败被判禁言（发送成功自动清除）——进去也发不出消息，优先级大幅降低；除非有必须看的未读，否则别选它白跑
            - **待办/记忆驱动**：待办**或短期记忆**里有未完成的任务（如「给XX发消息」「尚未XX」「按此号找他」）→ **优先打开能完成它的那个会话**，即使它没有未读——受人之托比凑热闹重要；目标在下方列表里就选它，**不在列表（如从未聊过的人）→ 用 proactive + 对方的 QQ号/群号**开新会话去办
            - **半途任务别丢**：日志显示你正办到一半（刚查到某人/QQ号）→ 下一轮**优先去目标会话把事办完**，别缩回热会话空转——查到的信息也在记忆里
            - **行尾（记忆：…）**是关于这个人/群你记得的事——选会话与执行任务直接参考、与待办互相印证；缺细节 → list_tools 找 MemoryQueryTool 关键词搜，搜到再据此行动
            - 工具调用**不限轮数**；「工具状态」**连续报错**时先读错误信息修正参数——连报 3 次说明方向错了，必须停用工具，直接给出 open_conversation 或 idle
            - 输出格式要求只是指令层，不是聊天内容；决策输出只用于选择会话，禁止把选择、选项、格式或本指令本身当成要说出去的话
        """.trimIndent().fixIndent()
    }

    /**
     * 指令与稳定状态（输出格式/约束/人格/状态）→ system 消息
     *
     * **按「稳定在前、易变在后」排布**（提高端点前缀缓存命中、省 token）：
     * 与 ConversationAgent 同款——时间、**心情**与操作日志等每轮变的内容压轴
     * （心情强度每条消息都 decay/boost，放前面会在人格后立刻断前缀），
     * 不变的输出格式/约束段在前，前缀每轮稳定。
     */
    private fun buildSystemPrompt(input: SessionPickerRequest): String =
        staticInstructions + "\n\n" + buildContextText(input)

    /** 动态状态段（人格/记忆/待办/操作日志/心情/时间）→ 每轮都变，压轴；native 模式随当轮 user 消息走 */
    private fun buildContextText(input: SessionPickerRequest): String {
        return """
            ## 上下文
            ### 你的信息
            ${input.persona.toPrompt()}
            ### 短期记忆
            ${input.shortTermMemory.ifBlank { "（暂无）" }}
            ### 待办
            ${input.todos.ifBlank { "（无待办）" }}
            ### 你最近的操作
            ${input.actionLog}
            - 心情：${input.mood.toPrompt()}
            当前时间（北京时间）：${input.timeHint}
        """.trimIndent().fixIndent()
    }

    /**
     * 剧情数据（工具目录/执行结果 + 未读列表 + 可主动找的人）→ user 消息
     *
     * 工具上下文含外来内容（查询结果可能整段是别人的聊天），与未读列表
     * 同放 user——system 只留指令与稳定状态。
     */
    private fun buildUserContent(input: SessionPickerRequest): String {
        fun render(s: SessionSummaryLine): String {
            val typeLabel = s.conversationType
            val unread = if (s.unreadCount > 0) "(${s.unreadCount}条未读)" else ""
            val at = when {
                s.isAtMe -> "(@我)"
                s.isAtAll -> "(@全体)"
                s.isReplyToMe -> "(@回复你)"
                else -> ""
            }
            // 我上次发言距今（null=还没说过话）——选会话优先级依据
            val spoke = s.botSpokeAgo?.let { "（我上次发言 $it）" } ?: "（我还没说过话）"
            // 疑似被禁言标记（日期=最近一次禁言失败的更新日期）
            val ban = s.suspectedBanDate?.let { "【疑似禁言 $it】" } ?: ""
            // 该会话主体的记忆摘要（最新几条单行——把记忆/待办驱动对上具体会话）
            val mem = s.memoryBrief?.let { "（记忆：$it）" } ?: ""
            return "- $typeLabel ${s.name}(${s.id})(${s.time})$unread$at$spoke$ban：${s.lastMessage}$mem"
        }

        // 未读与已读分区渲染——混在「未读消息」一个区里，模型会把**早看过**的
        // 最后一条（表情包/图片）当成未读新消息追进去回话（实盘 02:10：01:58
        // 已看过并决定不回的表情包，休眠重进被当成「新图片」又回了一遍）
        val unreadLines = input.sessions.filter { it.unreadCount > 0 }
        val readLines = input.sessions.filter { it.unreadCount == 0 }
        val sessionsText = when {
            input.sessions.isEmpty() -> "（没有未读）"
            else -> buildList {
                if (unreadLines.isNotEmpty()) {
                    add("### 未读消息\n" + unreadLines.joinToString("\n", transform = ::render))
                }
                if (readLines.isNotEmpty()) {
                    add("### 最近会话（已读，没有未读）\n" + readLines.joinToString("\n", transform = ::render))
                }
            }.joinToString("\n")
        }

        val proactiveText = if (input.proactiveCandidates.isEmpty()) {
            ""
        } else {
            val block = input.proactiveCandidates.joinToString("\n") {
                "- 私聊 ${it.name}(${it.id})：${it.hint}"
            }
            "\n### 可主动找的人（仅在空闲唤醒时出现）\n$block"
        }

        val cooldownText = if (input.cooldownNotes.isEmpty()) {
            ""
        } else {
            "\n### 刚退出（冷却中，**先别再进**）\n" +
                input.cooldownNotes.joinToString("\n") { "- $it" }
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
            $sessionsText$cooldownText$proactiveText
        """.trimIndent().fixIndent()
    }
}

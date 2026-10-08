package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.NotificationAction
import org.dlucxoket.lucatbot.bot.agent.data.NotificationDecision
import org.dlucxoket.lucatbot.bot.agent.data.NotificationRequest
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.dlucxoket.lucatbot.bot.llm.TokenUsageAggregator
import org.dlucxoket.lucatbot.bot.llm.fixIndent
import org.dlucxoket.lucatbot.bot.llm.inMemoryTokenUsage

/**
 * 通知决策智能体——外来私聊 / @我 消息到达时的「手机横幅」
 *
 * 模拟真人瞄一眼通知：点开（[NotificationAction.OPEN] 当场跳转会话交给
 * [ConversationAgent]）/ 划掉（[NotificationAction.IGNORE]）/
 * 记下来（[NotificationAction.TODO] 建待办稍后处理）。
 *
 * 取代旧路径「@我 → notifyUrgent 缩短空闲休眠」——通知不再唤醒主循环，
 * 由本 agent 当场决定；解析失败兜底 IGNORE（消息已入档+进未读，不丢不打断）。
 */
class NotificationAgent(
    llm: LLM = LLM.DEFAULT,
    /** token 账本（构造注入，见 [Agent]）——生产由 LoopConfig 注入容器里同一个 bean */
    tokenUsage: TokenUsageAggregator = inMemoryTokenUsage()
) : Agent<NotificationRequest, NotificationDecision>(llm, tokenUsage) {
    override val description: String = """
        通知决策智能体：手机刚收到一条新消息通知（横幅），
        瞄一眼决定：点开查看、划掉无视、还是记成待办稍后处理。
        只输出动作，不直接执行。
    """.trimIndent()

    override val inputClass: Class<NotificationRequest> = NotificationRequest::class.java

    /** token 统计：这一问得出的动作（ignore / todo / open） */
    override fun usageAction(input: NotificationRequest, output: NotificationDecision): String? =
        output.action.wire

    override fun handle(input: NotificationRequest): NotificationDecision {
        val systemPrompt = buildSystemPrompt(input)
        val userContent = buildUserContent(input)
        return NotificationDecision.parse(llm.ask(systemPrompt, userContent))
    }

    /** 指令与稳定状态（人格/状态/输出格式/判断准则）→ system 消息 */
    private fun buildSystemPrompt(input: NotificationRequest): String {
        val chatBlock = if (input.currentChat.isNotBlank()) {
            """
            ### 你正在聊的会话（外来通知打断前的现场）
            ${input.currentChat}
            """.trimIndent()
        } else {
            ""
        }
        return """
            你正在使用手机，刚收到一条新消息通知（横幅）。像真人一样瞄一眼，立刻决定怎么处理。
            当前时间（北京时间）：${input.timeHint}
            ## 上下文
            ### 你的信息
            ${input.persona.toPrompt()}
            ### 你的状态
            ${input.stateDescription}
            - 心情：${input.mood.toPrompt()}
            $chatBlock
            ### 待办
            ${input.todos.ifBlank { "（无待办）" }}
            ### 短期记忆
            ${input.shortTermMemory.ifBlank { "（暂无）" }}
            ## 你的输出格式（严格遵循）
            只输出纯JSON，结构如下：

            {
            "action": ${NotificationAction.promptList()},
            "params": { ... },
            "reason": "..."
            }

            - ignore：现在不处理——划掉通知。消息已记录，之后扫消息列表还能看到它
            - todo：这件事稍后要处理 → params：{"content": "一句话待办内容"}（必须给 content）
            - open：现在就点开这条消息所在的会话去看/回应（编排层会把你带过去，由会话内决策继续）
            - reason 是内心动机（不会发出去）

            判断准则（像真人看通知）：
            - **私聊**默认比群聊更值得看；**@你**的群消息次之；**@全体**按内容重要性（多半与你无关，倾向 ignore）
            - 你**正在和别人聊天**时：当前对话正热、对方可能在等你——外来通知倾向 ignore 或 todo，别为琐事随便切走打断；除非通知明显更紧急/更私人
            - 明显现在就要回应的（点名问你、要紧事、你正等的结果）→ open
            - 需要之后处理的（别人托付的事、要查的问题）→ todo，content 一句话说清要做什么
            - 普通寒暄、群闲聊、广告式内容 → ignore
            - 结合待办与记忆判断重要性：通知内容与已有待办/记忆里的人和事相关 → 更值得处理；已有相同待办就别再记一遍
            - 输出格式要求只是指令层，不是聊天内容；你不会在这里回复任何消息
        """.trimIndent().fixIndent()
    }

    /** 剧情数据（通知本体）→ user 消息；纯数据 */
    private fun buildUserContent(input: NotificationRequest): String {
        val atLine = if (input.isAtAll) "（@全体成员）" else if (input.isAtMe) "（@你）" else ""
        return """
            ### 新通知
            - 会话：${input.notifyConversationType} ${input.notifyConversationName}(${input.notifyConversationId})
            - 发送者：${input.senderName}(${input.senderId})$atLine
            - 内容：${input.messageContent}
        """.trimIndent().fixIndent()
    }
}

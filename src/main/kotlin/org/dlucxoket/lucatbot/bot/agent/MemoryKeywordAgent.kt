package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.MemoryKeywordRequest
import org.dlucxoket.lucatbot.bot.agent.data.MemoryKeywordResult
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.dlucxoket.lucatbot.bot.llm.TokenUsageAggregator
import org.dlucxoket.lucatbot.bot.llm.fixIndent
import org.dlucxoket.lucatbot.bot.llm.inMemoryTokenUsage

/**
 * 关键词拆分智能体——**进场记忆预搜的前置**（与退出复盘的 [MemoryAgent] 对称）
 *
 * 为什么单拎一个 agent（P2 的核心改动）：
 * 旧流程把「先搜记忆再说话」写死在 [ConversationAgent] 的 prompt 里——第一轮强制
 * `memory_queries` + `stay` 等结果，**每次进场都多烧一整轮全量决策 prompt**
 * （≈9.8k token/次）。现在进场先由本智能体把聊天记录**拆成可搜索的关键词**，
 * 编排层查库后把结果**与第一轮决策一起注入**——首轮即可回话，省掉一轮；
 * `memory_queries` 保留给会话中途补搜（见 ConversationAgent 的约束）。
 *
 * 体量优势：本智能体只做一件小事（输出一个词表），system 指令段远小于决策
 * 智能体——用它换掉一整轮决策，**每次进场净省约 6~7k token**。
 *
 * IO 契约照旧：system 放指令、user 放剧情数据（聊天记录），只输出纯 JSON。
 */
class MemoryKeywordAgent(
    llm: LLM = LLM.DEFAULT,
    /** token 账本（构造注入，见 [Agent]）——生产由 LoopConfig 注入容器里同一个 bean */
    tokenUsage: TokenUsageAggregator = inMemoryTokenUsage()
) : Agent<MemoryKeywordRequest, MemoryKeywordResult>(llm, tokenUsage) {

    override val description: String = """
        进场记忆预搜的关键词拆分智能体：
        读一段聊天记录，拆出值得去长期记忆里搜索的关键词（人名/地名/梗/约定/事物），
        交给编排层查库。纯寒暄、表情等没有可搜价值时输出空列表。
        一个词只做一件事——拆词，不判断要不要记、不生成回复。
    """.trimIndent()

    override val inputClass: Class<MemoryKeywordRequest> = MemoryKeywordRequest::class.java

    /** token 统计：这个 agent 只干一件事（拆词），动作标签恒定 */
    override fun usageAction(input: MemoryKeywordRequest, output: MemoryKeywordResult): String? =
        "拆词"

    override fun handle(input: MemoryKeywordRequest): MemoryKeywordResult =
        MemoryKeywordResult.parse(llm.ask(buildSystemPrompt(), buildUserContent(input)))

    /** 拆词规则（稳定）→ system 消息 */
    private fun buildSystemPrompt(): String {
        return """
            你在为一段聊天记录准备「回忆搜索关键词」——把该查的词交出去，交给系统去长期记忆里搜，**你自己不要回忆、不要编**。
            ## 输出（严格遵循）
            只输出纯 JSON（不裹代码块），结构如下：
            {"keywords": ["词1", "词2", …]}
            - 每个词**独立可搜**：单个词或短语，**不要整句**（搜的是记忆库，不是搜索引擎）
            - 建议 5~10 个，**宁多勿少**——搜不到没有代价，漏搜才会导致后面凭印象编
            - 取材：人名/外号/群名片/**QQ号**；地名、店名、群名/游戏名；**约定与事件**（明天、上次说的、要办的事）；正在聊的具体事物（梗、表情名、东西、话题）
            - **没有值得搜的**（刚开场的寒暄、纯表情、无信息量的句子）→ {"keywords": []}
            身份判定：同一个 QQ 号就是同一个人——网友改群名片/改名只是改名，拆它的旧名新名都行，但别拆成两个人
        """.trimIndent().fixIndent()
    }

    /** 聊天记录窗口（剧情数据）→ user 消息 */
    private fun buildUserContent(input: MemoryKeywordRequest): String {
        return """
            以下是当前会话的聊天记录：
            ${input.conversationContent.ifBlank { "（暂无内容）" }}
        """.trimIndent().fixIndent()
    }
}

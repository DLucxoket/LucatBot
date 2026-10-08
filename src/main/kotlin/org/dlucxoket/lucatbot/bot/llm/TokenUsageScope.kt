package org.dlucxoket.lucatbot.bot.llm

/**
 * token 用量的**维度上下文**（ThreadLocal）——横切关注点的唯一环境缝
 *
 * 为什么要这一层：`OpenAIAPI.ask()` 只看得到 prompt 与响应，不知道「是谁在问、
 * 问的是哪个会话」——而统计要的正是这两个维度。调用方（Agent / 编排层）在
 * 调 LLM 之前把维度挂到当前线程，`OpenAIAPI` 拿到 tokenUsage 时顺手取走，
 * **LLM 接口本身不加参数**（ISP：`LLM` 只暴露 `ask()`）。
 *
 * 线程内可嵌套（`try/finally` 还原上一层），执行完即还原，不污染同线程后续调用。
 *
 * 两个维度与挂载点：
 * - **agent**：[org.dlucxoket.lucatbot.bot.agent.Agent] 入口统一设置
 *   （默认类名，如 `ConversationAgent`）
 * - **conversation**：优先由 Agent 的 `conversationTag(input)` 从入参推导
 *   （input 自带会话 id 的场景）；推不出来的（通知、记忆整理、戳一戳等）
 *   由编排层在调 Agent 前 `withConversation(...)` 挂上——会话键 `private:123` /
 *   `group:456`、`消息列表`、`通知:private:123`
 *
 * 为什么它仍是全局对象而不是注入：环境态天然绑定「当前线程」，注入进来也无处安放；
 * 仓库里同类先例是 [VisionSupport]。**除它之外，token 统计的其余部件一律构造注入。**
 */
object TokenUsageScope {
    private val agentTag = ThreadLocal.withInitial { "unknown" }
    private val conversationTag = ThreadLocal.withInitial { "-" }

    /** 当前线程的「agent」维度（默认 unknown = 没经过 Agent 入口） */
    fun agent(): String = agentTag.get()

    /** 当前线程的「会话」维度（默认 "-" = 没经 Agent/编排层设置） */
    fun conversation(): String = conversationTag.get()

    /** 在 [block] 期间把 agent 维度设为 [name]（执行完还原上一层） */
    fun <T> withAgent(name: String, block: () -> T): T {
        val prev = agentTag.get()
        agentTag.set(name)
        return try {
            block()
        } finally {
            agentTag.set(prev)
        }
    }

    /** 在 [block] 期间把会话维度设为 [conversation]（执行完还原上一层） */
    fun <T> withConversation(conversation: String, block: () -> T): T {
        val prev = conversationTag.get()
        conversationTag.set(conversation)
        return try {
            block()
        } finally {
            conversationTag.set(prev)
        }
    }

    // ------------------------------------------------------------------
    // 用量暂存（**记录时机上移**：传输层存、Agent 出来时带决策标签记账）
    // ------------------------------------------------------------------

    private val pending = ThreadLocal.withInitial { mutableListOf<TokenUsagePending>() }

    /**
     * [org.dlucxoket.lucatbot.bot.llm.OpenAIAPI] 拿到 usage 后暂存（本线程）
     *
     * 维度（agent/会话）此刻已挂在 [TokenUsageScope] 上，flush 时一并读——
     * 明细的 `action`/`firstRound` 只有产出决策的 Agent 知道，故延后到那里记账
     */
    fun stashUsage(usage: TokenUsagePending) {
        pending.get() += usage
    }

    /** 取走本线程暂存的用量（[org.dlucxoket.lucatbot.bot.agent.Agent] 记账后即空） */
    fun drainUsage(): List<TokenUsagePending> {
        val bucket = pending.get()
        if (bucket.isEmpty()) return emptyList()
        val copy = bucket.toList()
        bucket.clear()
        return copy
    }
}

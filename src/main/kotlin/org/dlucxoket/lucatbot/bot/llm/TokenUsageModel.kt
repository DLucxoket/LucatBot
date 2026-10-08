package org.dlucxoket.lucatbot.bot.llm

/**
 * token 用量的**数据模型**（纯数据、无行为依赖——存储与聚合共用）
 */

/** 通道维度：文字 / 图片（带图请求的视觉 token 计在入参里，靠通道才分得出来） */
enum class TokenUsageChannel(val label: String) {
    TEXT("文字"), IMAGE("图片");

    companion object {
        /** 按中文标签反查（读存储时用；未知标签回落 [TEXT]） */
        fun fromLabel(label: String?): TokenUsageChannel =
            entries.firstOrNull { it.label == label } ?: TEXT
    }
}

/**
 * 一次调用的**原始用量**——`OpenAIAPI` 拿到 usage 后先存进 [TokenUsageScope]，
 * 由产出这次调用的 [org.dlucxoket.lucatbot.bot.agent.Agent] 在 handle 结束时统一记账
 *
 * 为什么要过一道手（而不是 LLM 传输层直接记）：账要带**决策结果**（这一问得出了
 * send/idle/…），只有问的人知道；传输层记完再回头补标就得改写 append-only 明细。
 *
 * @param input 入参 token（端点未回 = null）
 * @param output 出参 token（端点未回 = null）
 */
data class TokenUsagePending(
    val channel: TokenUsageChannel,
    val model: String,
    val input: Long?,
    val output: Long?
)

/**
 * 一个统计键的累计值——聚合快照的一行
 *
 * @param total 入参 + 出参（派生属性，序列化给前端）
 */
data class TokenUsageEntry(
    val agent: String,
    val conversation: String,
    val channel: String,
    val model: String,
    /** 这次调用产出的决策动作（send / idle / read_more…；没打上 = "-"） */
    val action: String,
    /** 是否本会话首轮（进场首轮占比统计） */
    val firstRound: Boolean,
    val calls: Long,
    val input: Long,
    val output: Long
) {
    val total: Long get() = input + output
}

/**
 * 一次调用的明细——**存储与展示共用的一行**（jsonl 的一行 = 这个对象）
 *
 * @param reported false = 端点没回 usage（token 按 0 计，前端如实标）
 * @param action 决策动作标签（旧格式行没有该字段 → "-"）
 * @param firstRound 进场首轮（旧格式行 → false）
 */
data class TokenUsageCall(
    val epoch: Long,
    val agent: String,
    val conversation: String,
    val channel: String,
    val model: String,
    val input: Long,
    val output: Long,
    val reported: Boolean,
    val action: String = "-",
    val firstRound: Boolean = false
) {
    val total: Long get() = input + output
}

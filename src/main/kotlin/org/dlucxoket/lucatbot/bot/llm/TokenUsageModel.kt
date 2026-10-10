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
 * @param durationMs 本次调用耗时（毫秒；含补一次重试的 300ms 间隔）
 * @param request **发给 LLM 的真实请求**（system/user 原文；图片以 `[图片 ref]`
 *   标签呈现、data URI 以占位说明代替——正文逐字，二进制不入库）
 * @param response 模型的回复正文（端点异常/空响应 = 空串）
 * @param error 端点异常信息（成功 = null；降级行带上，面板可见失败原因）
 * @param cached prompt **缓存命中**的输入 token（langchain4j 1.x 起才解析得到；
 *   端点不回/非 OpenAI 实现 = null）——入参的子集，不是额外计费
 */
data class TokenUsagePending(
    val channel: TokenUsageChannel,
    val model: String,
    val input: Long?,
    val output: Long?,
    val durationMs: Long? = null,
    val request: String? = null,
    val response: String? = null,
    val error: String? = null,
    val cached: Long? = null
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
    val output: Long,
    /** 入参里命中缓存的 token（**按模型计费**要用——命中档单价差 50~120 倍） */
    val cached: Long = 0
) {
    val total: Long get() = input + output
}

/**
 * 一次调用的明细——**存储与展示共用的一行**（jsonl 的一行 / token_usage 表的一行）
 *
 * @param reported false = 端点没回 usage（token 按 0 计，前端如实标）
 * @param action 决策动作标签（旧格式行没有该字段 → "-"）
 * @param firstRound 进场首轮（旧格式行 → false）
 * @param id 存储侧主键（MySQL 自增 id / jsonl 行号 / 内存序号）——**轨迹详情
 *   按 id 取**；null = 旧数据没编号
 * @param durationMs 调用耗时（毫秒；旧数据 → null）
 * @param request/response/error 大字段：**明细列表接口不带**（省流量），
 *   轨迹详情接口按 id 单取（`TokenUsageStore.find`）
 * @param cached prompt 缓存命中的输入 token（入参子集；端点不回/旧数据 = null）
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
    val firstRound: Boolean = false,
    val id: Long? = null,
    val durationMs: Long? = null,
    val request: String? = null,
    val response: String? = null,
    val error: String? = null,
    val cached: Long? = null
) {
    val total: Long get() = input + output
}

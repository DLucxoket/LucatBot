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
 * 一个统计键（agent × 会话 × 通道 × 模型）的累计值——聚合快照的一行
 *
 * @param total 入参 + 出参（派生属性，序列化给前端）
 */
data class TokenUsageEntry(
    val agent: String,
    val conversation: String,
    val channel: String,
    val model: String,
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
 */
data class TokenUsageCall(
    val epoch: Long,
    val agent: String,
    val conversation: String,
    val channel: String,
    val model: String,
    val input: Long,
    val output: Long,
    val reported: Boolean
) {
    val total: Long get() = input + output
}

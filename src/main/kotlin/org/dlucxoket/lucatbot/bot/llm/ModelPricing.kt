package org.dlucxoket.lucatbot.bot.llm

/**
 * 一个模型的**三档单价**（每 1M token 的计费单位数——单位见 [PricingTable.unit]，
 * Token Plan 是 Credits、有的平台是元）
 *
 * 三档对应三类输入/输出（Token Plan 实测：命中缓存的输入只扣 1/50~1/120）：
 * - [inputPer1M]  输入·**未命中缓存**
 * - [cachedPer1M] 输入·**命中缓存**（null = 那段按未命中价、不打折）
 * - [outputPer1M] 输出
 */
data class ModelRates(
    val inputPer1M: Double,
    val outputPer1M: Double,
    val cachedPer1M: Double? = null
)

/**
 * 当前平台的**计费表**——按模型名查价、三档计费（费用在**读侧现算不落库**，
 * 改价后历史用量按新价重估）
 *
 * 来源：`llm.platforms.<active>.pricing`（`unit` + `models.<模型名>.{input,cached,output}-per-1m`）。
 * **没配价的模型 = estimate 返回 null（不猜价）**——历史里别的平台/模型的行
 * 不计入估算费用，面板如实标「仅计已配价模型」。
 *
 * @param unit 计费单位（"Credits" / "元"…；空串 = 没配）
 * @param models 模型名 → 三档单价（键与 usage 行的 `model` 字段精确匹配）
 */
data class PricingTable(
    val unit: String = "",
    val models: Map<String, ModelRates> = emptyMap()
) {
    /** 是否配了至少一个模型的价（决定面板显不显示费用） */
    val configured: Boolean get() = models.isNotEmpty()

    /**
     * 估算一段统计口径的消耗：入参拆「未命中 / 命中缓存」两段分别计价 + 出参计价
     *
     * @param model usage 行的模型名（查不到价 = 不猜，返回 null）
     * @param cached 入参里缓存命中的 token（入参子集；异常超入参按入参截断）
     * @return null = 该模型没配价
     */
    fun estimate(model: String, input: Long, output: Long, cached: Long? = null): Double? {
        val rates = models[model] ?: return null
        val hit = (cached ?: 0L).coerceIn(0L, input)
        val cacheRate = rates.cachedPer1M ?: rates.inputPer1M
        return (input - hit) / 1_000_000.0 * rates.inputPer1M +
            hit / 1_000_000.0 * cacheRate +
            output / 1_000_000.0 * rates.outputPer1M
    }
}

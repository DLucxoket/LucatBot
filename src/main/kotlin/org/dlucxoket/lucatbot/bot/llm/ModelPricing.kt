package org.dlucxoket.lucatbot.bot.llm

/**
 * 当前平台模型的**单价**（元 / 百万 token）——费用**估算**用
 *
 * 来源：`application.yaml` 的 `llm.platforms.<active>.pricing.*`（填官网价）。
 * **未配置 = 不算不猜**：面板不显示费用，绝不编造价格；费用在**读侧现算不落库**，
 * 改价后历史用量按新价重估。
 *
 * @param inputPer1M  入参单价（元/百万 token）
 * @param outputPer1M 出参单价
 * @param cachedPer1M 缓存读取折扣价（配合 `cached_tokens`；null = 那段按入参价、不打折）
 */
data class ModelPricing(
    val inputPer1M: Double? = null,
    val outputPer1M: Double? = null,
    val cachedPer1M: Double? = null
) {
    /**
     * 估算一段统计口径的费用（元）：入参拆「缓存命中 / 新入参」两段计价 + 出参计价
     *
     * @param cached 入参里缓存命中的 token（入参的子集；异常超入参时按入参截断）
     * @return null = 单价没配全（input/output 必须都填），不猜价
     */
    fun estimate(input: Long, output: Long, cached: Long = 0L): Double? {
        val inPrice = inputPer1M ?: return null
        val outPrice = outputPer1M ?: return null
        val cachePrice = cachedPer1M ?: inPrice
        val hit = cached.coerceIn(0L, input)
        val fresh = input - hit
        return fresh / 1_000_000.0 * inPrice +
            hit / 1_000_000.0 * cachePrice +
            output / 1_000_000.0 * outPrice
    }
}

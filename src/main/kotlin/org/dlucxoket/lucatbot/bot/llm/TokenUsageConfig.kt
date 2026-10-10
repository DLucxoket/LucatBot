package org.dlucxoket.lucatbot.bot.llm

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.Environment

/**
 * token 用量统计的 Bean 装配（**DIP 落点**）
 *
 * 显式 @Bean 而不是 @Component：聚合器依赖存储接口，两个 bean 一条线写清楚，
 * 单测直接 `new TokenUsageAggregator(替身存储)`——不再像旧版单例那样靠
 * `configure()/reset()` 改全局状态来隔离。
 *
 * **存储实现按 `storage.type` 绑定**（`StorageConfig.tokenUsageStore`：
 * mysql → `token_usage` 表（带请求/响应正文轨迹）、json → data/token-usage.jsonl；
 * 见 [TokenUsageAggregator]）——本配置只装配聚合器与**单价**（费用估算用）。
 */
@Configuration
class TokenUsageConfig(
    /** 平台选择：读 `llm.active` + `llm.platforms.<active>.pricing.*` */
    private val environment: Environment
) {

    @Bean
    fun tokenUsageAggregator(tokenUsageStore: TokenUsageStore): TokenUsageAggregator =
        TokenUsageAggregator(tokenUsageStore)

    /**
     * 当前平台**计费表**（`unit` + 按模型的三档单价）——没配价 = 空表 =
     * 面板不显示估算消耗（不猜价）；换平台/改价改 yaml 重启即生效
     *（读侧现算，历史用量按新价重估）。
     *
     * 两种配法并存：
     * - **按模型三档**（Credits 类）：`models.<模型名>.{input,cached,output}-per-1m`
     * - **按 token 平铺**（元/百万 token 类）：`{input,cached,output}-per-1m`——
     *   作用于该平台 `model-name` 对应的模型（已单列模型价时以模型价为准）
     *
     * 只装 **active 平台**的表：单位不同（Credits vs 元）不能混着加——
     * 换平台重启后，按模型名匹配，历史里属于该平台模型的行自动恢复计价。
     */
    @Bean
    fun pricingTable(): PricingTable {
        val active = environment.getProperty("llm.active")?.takeIf { it.isNotBlank() } ?: "mimo"
        val prefix = "llm.platforms.$active.pricing."
        fun num(key: String): Double? = environment.getProperty(key)?.trim()?.toDoubleOrNull()
        val unit = environment.getProperty("${prefix}unit")?.trim().orEmpty()
        // 枚举 models.<模型名>.<字段> 叶子键（模型名可能自带点，按已知字段后缀剥离，
        // 不是按第一个点切）；半截配置（缺 input/output）整条忽略，由平铺兜底。
        // 注意：Environment 本身既不是 PropertySource、也没暴露 propertySources——
        // 转成 ConfigurableEnvironment 遍历它的源（Boot 的 YAML 源可枚举）
        val fields = setOf("input-per-1m", "output-per-1m", "cached-per-1m")
        val names = (environment as? ConfigurableEnvironment)?.propertySources
            ?.filterIsInstance<EnumerablePropertySource<*>>()
            ?.flatMap { it.propertyNames.toList() }
            ?.filter { it.startsWith("${prefix}models.") }
            ?.distinct() ?: emptyList()
        val byModel = mutableMapOf<String, MutableMap<String, Double>>()
        for (full in names) {
            val rest = full.removePrefix("${prefix}models.")
            val field = fields.firstOrNull { rest.endsWith(".$it") } ?: continue
            val value = num(full) ?: continue
            byModel.getOrPut(rest.removeSuffix(".$field")) { mutableMapOf() }[field] = value
        }
        val models = byModel.mapNotNull { (model, f) ->
            val input = f["input-per-1m"] ?: return@mapNotNull null
            val output = f["output-per-1m"] ?: return@mapNotNull null
            model to ModelRates(
                inputPer1M = input,
                outputPer1M = output,
                cachedPer1M = f["cached-per-1m"]
            )
        }.toMap().toMutableMap()
        // 按 token 平铺单价 → 挂到该平台 model-name 的模型上（已单列则不覆盖）
        val flatInput = num("${prefix}input-per-1m")
        val flatOutput = num("${prefix}output-per-1m")
        if (flatInput != null && flatOutput != null) {
            val modelName = environment.getProperty("llm.platforms.$active.model-name")
                ?.takeIf { it.isNotBlank() }
            if (modelName != null && modelName !in models) {
                models[modelName] = ModelRates(
                    inputPer1M = flatInput,
                    outputPer1M = flatOutput,
                    cachedPer1M = num("${prefix}cached-per-1m")
                )
            }
        }
        return PricingTable(unit = unit, models = models)
    }
}

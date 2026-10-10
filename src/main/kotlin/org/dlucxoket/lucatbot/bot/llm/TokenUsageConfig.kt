package org.dlucxoket.lucatbot.bot.llm

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
     * 当前平台模型单价——**未配置 = 全 null = 面板不显示估算费用（不猜价）**；
     * 换平台/改价改 yaml 重启即生效（读侧现算，历史用量按新价重估）
     */
    @Bean
    fun modelPricing(): ModelPricing {
        val active = environment.getProperty("llm.active")?.takeIf { it.isNotBlank() } ?: "mimo"
        fun price(key: String): Double? =
            environment.getProperty("llm.platforms.$active.pricing.$key")?.trim()?.toDoubleOrNull()
        return ModelPricing(
            inputPer1M = price("input-per-1m"),
            outputPer1M = price("output-per-1m"),
            cachedPer1M = price("cached-per-1m")
        )
    }
}

package org.dlucxoket.lucatbot.bot.llm

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * token 用量统计的 Bean 装配（**DIP 落点**）
 *
 * 显式 @Bean 而不是 @Component：聚合器依赖存储接口，两个 bean 一条线写清楚，
 * 单测直接 `new TokenUsageAggregator(替身存储)`——不再像旧版单例那样靠
 * `configure()/reset()` 改全局状态来隔离。
 *
 * **存储实现按 `storage.type` 绑定**（`StorageConfig.tokenUsageStore`：
 * mysql → `token_usage` 表（带请求/响应正文轨迹）、json → data/token-usage.jsonl；
 * 见 [TokenUsageAggregator]）——本配置只装配聚合器。
 */
@Configuration
class TokenUsageConfig {

    @Bean
    fun tokenUsageAggregator(tokenUsageStore: TokenUsageStore): TokenUsageAggregator =
        TokenUsageAggregator(tokenUsageStore)
}

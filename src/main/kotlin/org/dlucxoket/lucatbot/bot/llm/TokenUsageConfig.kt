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
 * 存储路径是观测数据、不进 `storage.type` 后端（见 [TokenUsageAggregator]）。
 */
@Configuration
class TokenUsageConfig {

    @Bean
    fun tokenUsageStore(): TokenUsageStore = JsonlTokenUsageStore("data/token-usage.jsonl")

    @Bean
    fun tokenUsageAggregator(tokenUsageStore: TokenUsageStore): TokenUsageAggregator =
        TokenUsageAggregator(tokenUsageStore)
}

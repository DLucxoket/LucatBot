package org.dlucxoket.lucatbot.bot

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryRepository
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 存储实现的 Bean 配置
 *
 * 遵循 DIP：Service 依赖接口，具体实现在这里绑定。
 * 将来迁移到 SQLite/Redis 时只需修改此配置。
 */
@Configuration
class StorageConfig {

    @Bean
    fun memoryRepository(): MemoryRepository {
        return JsonFileMemoryRepository()
    }

    @Bean
    fun chatHistoryRepository(): ChatHistoryRepository {
        return JsonFileChatHistoryRepository()
    }
}

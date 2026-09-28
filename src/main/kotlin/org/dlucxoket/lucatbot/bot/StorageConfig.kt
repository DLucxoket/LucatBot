package org.dlucxoket.lucatbot.bot

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryRepository
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryRepository
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 存储实现的 Bean 配置
 *
 * 遵循 DIP：Service 依赖接口，具体实现在这里绑定。
 * 将来迁移到 SQLite/Redis 时只需修改此配置。
 *
 * destroyMethod 兜底：Spring 容器关闭时把防抖未落盘的数据写盘。
 */
@Configuration
class StorageConfig {

    @Bean(destroyMethod = "shutdown")
    fun memoryRepository(): MemoryRepository {
        return JsonFileMemoryRepository()
    }

    @Bean(destroyMethod = "shutdown")
    fun chatHistoryRepository(): ChatHistoryRepository {
        return JsonFileChatHistoryRepository()
    }

    @Bean(destroyMethod = "shutdown")
    fun todoRepository(): TodoRepository {
        return JsonFileTodoRepository()
    }
}

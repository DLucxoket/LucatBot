package org.dlucxoket.lucatbot.bot

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryRepository
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryRepository
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlChatHistoryRepository
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlConfig
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlMemoryRepository
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlStorage
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlTodoRepository
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 存储实现的 Bean 配置
 *
 * 遵循 DIP：Service 依赖接口，具体实现在这里按 `storage.type` 绑定——
 * **json**（data 目录下的 json 文件，原实现）或 **mysql**（短连接直写，写入即落库）。
 * 两套实现同一接口，业务层无感知；新增后端（SQLite/Redis）只需再加一个分支。
 *
 * MySQL 模式：首次作为依赖被创建时 [MySqlStorage.init] 引导
 * （建库 → 建表 → 空表时从 data 目录的 json 文件迁入存量数据），连不上直接抛——**启动即报错**，
 * 不静默降级掩盖配置问题。
 *
 * destroyMethod 兜底：Spring 容器关闭时把未落盘的数据写盘
 * （JSON 实现有防抖需要；MySQL 实现 shutdown/flush 均为 no-op）。
 */
@Configuration
class StorageConfig(
    @Value("\${storage.type:json}") private val storageType: String,
    @Value("\${storage.mysql.host:localhost}") private val mysqlHost: String,
    @Value("\${storage.mysql.port:3306}") private val mysqlPort: Int,
    @Value("\${storage.mysql.database:LucatBot}") private val mysqlDatabase: String,
    @Value("\${storage.mysql.username:root}") private val mysqlUsername: String,
    @Value("\${storage.mysql.password:}") private val mysqlPassword: String
) {
    private val log = LoggerFactory.getLogger(StorageConfig::class.java)

    private val useMySql: Boolean
        get() = storageType.equals("mysql", ignoreCase = true)

    /** MySQL 后端（仅 mysql 模式创建；首次访问时引导建库建表与 JSON 迁移） */
    private val mysql: MySqlStorage by lazy {
        MySqlStorage(
            MySqlConfig(
                host = mysqlHost,
                port = mysqlPort,
                database = mysqlDatabase,
                username = mysqlUsername,
                password = mysqlPassword,
                importFromJson = true
            )
        ).also { it.init() }
    }

    @Bean(destroyMethod = "shutdown")
    fun memoryRepository(): MemoryRepository =
        if (useMySql) MySqlMemoryRepository(mysql) else JsonFileMemoryRepository()

    @Bean(destroyMethod = "shutdown")
    fun chatHistoryRepository(): ChatHistoryRepository =
        if (useMySql) MySqlChatHistoryRepository(mysql) else JsonFileChatHistoryRepository()

    @Bean(destroyMethod = "shutdown")
    fun todoRepository(): TodoRepository =
        if (useMySql) MySqlTodoRepository(mysql) else JsonFileTodoRepository()

    init {
        log.info(
            "存储后端: {}",
            if (useMySql) "MySQL ${mysqlUsername}@$mysqlHost:$mysqlPort/$mysqlDatabase"
            else "JSON 文件（data/*.json）"
        )
    }
}

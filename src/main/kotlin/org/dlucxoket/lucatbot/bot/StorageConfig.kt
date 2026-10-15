package org.dlucxoket.lucatbot.bot

import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryFold
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryRepository
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryRepository
import org.dlucxoket.lucatbot.bot.storage.JsonFiles
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlChatHistoryRepository
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlConfig
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlMemoryRepository
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlStorage
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlTodoRepository
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
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

    /** 已读水位（第六张表/data/read-state.json）——重启后据此重建未读 */
    @Bean(destroyMethod = "shutdown")
    fun readStateRepository(): org.dlucxoket.lucatbot.bot.chathistory.ReadStateRepository =
        if (useMySql) {
            org.dlucxoket.lucatbot.bot.storage.mysql.MySqlReadStateRepository(mysql)
        } else {
            org.dlucxoket.lucatbot.bot.chathistory.JsonFileReadStateRepository()
        }

    /** 操作日志（第七张表 action_log / data/action-log.json）——结构化四列 */
    @Bean(destroyMethod = "shutdown")
    fun actionLogRepository(): org.dlucxoket.lucatbot.bot.loop.ActionLogRepository =
        if (useMySql) {
            org.dlucxoket.lucatbot.bot.storage.mysql.MySqlActionLogRepository(mysql)
        } else {
            org.dlucxoket.lucatbot.bot.loop.JsonFileActionLogRepository()
        }

    /**
     * 固定短记（第八张表 fixed_memory / data/fixed-memory.json）——
     * **只增不删**：每次更新 INSERT 一行，旧行即变更审计；当前 = 最新一条
     */
    @Bean(destroyMethod = "shutdown")
    fun fixedMemoryRepository(): org.dlucxoket.lucatbot.bot.memory.FixedMemoryRepository =
        if (useMySql) {
            org.dlucxoket.lucatbot.bot.storage.mysql.MySqlFixedMemoryRepository(mysql)
        } else {
            org.dlucxoket.lucatbot.bot.memory.JsonFileFixedMemoryRepository()
        }

    /**
     * token 用量明细（第九张表 `token_usage` / `data/token-usage.jsonl`）——
     * **随行带轨迹**：发给 LLM 的真实请求、模型回复、耗时、异常一并落库，
     * Web「调用轨迹」按 id 取详情。mysql 首启空表时把 jsonl 存量整份导入
     * （见 MySqlStorage.importTokenUsageJsonl），此后 jsonl 只读留档。
     */
    @Bean
    fun tokenUsageStore(): org.dlucxoket.lucatbot.bot.llm.TokenUsageStore =
        if (useMySql) {
            org.dlucxoket.lucatbot.bot.storage.mysql.MySqlTokenUsageStore(mysql)
        } else {
            org.dlucxoket.lucatbot.bot.llm.JsonlTokenUsageStore("data/token-usage.jsonl")
        }

    /**
     * 启动清理：把聊天记录存储层的孪生双份行合并为一条（仅 mysql 模式）
     *
     * 实时/回执行 × 爬楼 `hist:` 行的存量双份一直双倍膨胀——启动时用
     * [ChatHistoryFold] 规则收敛（id 以 `msg:` 为准）。
     *
     * **与 json 同步的配合**（曾出过每次启动固定 31 行的删/插拉锯）：
     * 被清理删掉的行若还躺在迁移源 `data/chat-history.json` 里，下次启动
     * 的「补缺失」导入会原样灌回来、清理再删——互为逆操作永远收敛不了；
     * 且 json 自身折叠救不了：孪生幸存方是库内运行时行、json 里只有孤行，
     * 折叠数恒为 0。回灌已由 **json 导入侧孪生感知**阻断（`planChatImport`：
     * 库里已有同一物理消息的行就不再补）。这里随后折叠迁移源 json 只剩一个
     * 兜底用途——清掉 json **内部**的孪生对（重建空库时少灌双份）。
     *
     * json 模式（含 @SpringBootTest 上下文）不跑：避免触碰正在活跃写入的
     * 真实 json 文件。清理失败只 warn，绝不阻塞启动。
     */
    @Bean
    fun chatHistoryCollapseRunner(chatHistoryService: ChatHistoryService): ApplicationRunner = ApplicationRunner {
        if (!useMySql) return@ApplicationRunner
        runCatching {
            chatHistoryService.collapseStoredTwins()
            collapseSourceJsonTwins()
        }.onFailure { log.warn("聊天记录双份行清理失败（跳过，不影响启动）: {}", it.message) }
    }

    /** 折叠迁移源 data/chat-history.json 里的孪生双份（重建空库少灌双份；回灌拉锯已由导入侧孪生感知阻断） */
    private fun collapseSourceJsonTwins() {
        val file = java.io.File("data/chat-history.json")
        if (!file.exists()) return
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val rows = runCatching {
            mapper.readValue<List<ChatMessageRecord>>(file)
        }.getOrElse { e ->
            log.warn("迁移源 json 解析失败，跳过孪生折叠: {}", e.message)
            return
        }
        val folded = rows.groupBy { it.conversationId }
            .values.flatMap { ChatHistoryFold.fold(it).kept }
        if (folded.size >= rows.size) return
        JsonFiles.writeAtomic(mapper, file, folded)
        log.info("迁移源 json 孪生折叠：{} → {} 条", rows.size, folded.size)
    }

    init {
        log.info(
            "存储后端: {}",
            if (useMySql) "MySQL ${mysqlUsername}@$mysqlHost:$mysqlPort/$mysqlDatabase"
            else "JSON 文件（data/*.json）"
        )
    }
}

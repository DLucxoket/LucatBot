package org.dlucxoket.lucatbot.bot.todo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.storage.DebouncedFlusher
import org.dlucxoket.lucatbot.bot.storage.JsonFiles
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * JSON 文件待办仓库
 *
 * **OOP 设计**：
 * - 实现 [TodoRepository] 接口（依赖倒置）
 * - 单一职责：只管 JSON 文件存取
 * - 内存缓存 + 防抖落盘（写入后约 5s 批量 flush，进程退出兜底写盘）
 *
 * 存储格式：`data/todos.json`
 * 未来可替换为 SQLite/Redis 等实现，业务层无感知。
 */
class JsonFileTodoRepository(
    private val filePath: String = "data/todos.json"
) : TodoRepository {

    private val log = LoggerFactory.getLogger(JsonFileTodoRepository::class.java)
    private val mapper = jacksonObjectMapper()

    /** 内存缓存（线程安全） */
    private val cache = ConcurrentHashMap<String, TodoItem>()

    /** 是否有未持久化的变更 */
    @Volatile
    private var dirty = false

    /** 防抖落盘 */
    private val flusher = DebouncedFlusher(
        flushAction = { doFlush() },
        delayMs = 5_000L,
        name = "todo-flush"
    )

    init {
        load()
    }

    override fun save(todo: TodoItem) {
        cache[todo.id] = todo
        dirty = true
        flusher.markDirty()
    }

    override fun delete(id: String) {
        if (cache.remove(id) != null) {
            dirty = true
            flusher.markDirty() // 与 save 同一条防抖落盘链路（约 5s 后写盘，退出兜底）
        }
    }

    override fun findAll(): List<TodoItem> {
        return cache.values.toList()
    }

    override fun flush() {
        flusher.flushNow()  // 走互斥锁，避免与防抖调度线程并发写同一 .tmp
    }

    /** 进程退出兜底：立即写盘并关闭防抖调度器 */
    fun shutdown() {
        flusher.shutdown()
    }

    private fun doFlush() {
        if (!dirty) return
        // 先清脏标志再写：写入期间发生的新 save() 会重新置位，不会被误清后丢一轮落盘
        dirty = false
        try {
            JsonFiles.writeAtomic(mapper, File(filePath), cache.values.toList())
            log.debug("待办已持久化到 $filePath（${cache.size} 条）")
        } catch (e: Exception) {
            dirty = true  // 写失败恢复标志，等待下次 flush / 退出兜底重试
            log.error("待办持久化失败: ${e.message}")
        }
    }

    /**
     * 从文件加载待办
     */
    private fun load() {
        val file = File(filePath)
        if (!file.exists()) {
            log.info("待办文件不存在，创建新的: $filePath")
            return
        }
        try {
            val todos: List<TodoItem> = mapper.readValue(file)
            todos.forEach { cache[it.id] = it }
            log.info("已加载 ${todos.size} 条待办")
        } catch (e: Exception) {
            // 加载失败绝不能让空缓存覆盖原文件：先改名备份，再以空缓存启动
            JsonFiles.backupCorrupt(file)
            log.error("待办加载失败（${e.message}）：以空缓存启动，原文件已备份为 .corrupt-*，不会被覆盖")
        }
    }
}

package org.dlucxoket.lucatbot.bot.todo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.storage.DebouncedFlusher
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

    override fun saveAll(todos: List<TodoItem>) {
        todos.forEach { cache[it.id] = it }
        dirty = true
        flusher.markDirty()
    }

    override fun findAll(): List<TodoItem> {
        return cache.values.toList()
    }

    override fun delete(id: String) {
        cache.remove(id)
        dirty = true
        flusher.markDirty()
    }

    override fun flush() {
        doFlush()
    }

    /** 进程退出兜底：立即写盘并关闭防抖调度器 */
    fun shutdown() {
        flusher.shutdown()
    }

    private fun doFlush() {
        if (!dirty) return
        try {
            val file = File(filePath)
            file.parentFile?.mkdirs()
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, cache.values.toList())
            dirty = false
            log.debug("待办已持久化到 $filePath（${cache.size} 条）")
        } catch (e: Exception) {
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
            log.error("待办加载失败: ${e.message}")
        }
    }
}

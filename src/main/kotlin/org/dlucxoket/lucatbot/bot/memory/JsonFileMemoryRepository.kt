package org.dlucxoket.lucatbot.bot.memory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.storage.DebouncedFlusher
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * JSON 文件记忆仓库
 *
 * **OOP 设计**：
 * - 实现 [MemoryRepository] 接口（依赖倒置）
 * - 单一职责：只管 JSON 文件存取
 * - 内存缓存 + 防抖落盘（写入后约 5s 批量 flush，进程退出兜底写盘）
 *
 * 存储格式：`data/memories.json`
 * 未来可替换为 SQLite/Redis 等实现，业务层无感知。
 */
class JsonFileMemoryRepository(
    private val filePath: String = "data/memories.json"
) : MemoryRepository {

    private val log = LoggerFactory.getLogger(JsonFileMemoryRepository::class.java)
    private val mapper = jacksonObjectMapper()

    /** 内存缓存（线程安全） */
    private val cache = ConcurrentHashMap<String, MemoryItem>()

    /** 是否有未持久化的变更 */
    @Volatile
    private var dirty = false

    /** 防抖落盘 */
    private val flusher = DebouncedFlusher(
        flushAction = { doFlush() },
        delayMs = 5_000L,
        name = "memory-flush"
    )

    init {
        load()
    }

    override fun save(memory: MemoryItem) {
        cache[memory.id] = memory
        dirty = true
        flusher.markDirty()
    }

    override fun saveAll(memories: List<MemoryItem>) {
        memories.forEach { cache[it.id] = it }
        dirty = true
        flusher.markDirty()
    }

    override fun findById(id: String): MemoryItem? {
        return cache[id]
    }

    override fun findByUserId(userId: Long): List<MemoryItem> {
        return cache.values.filter { it.userId == userId }
    }

    override fun findByTags(tags: List<String>): List<MemoryItem> {
        return cache.values.filter { item ->
            item.tags.any { tag -> tags.contains(tag) }
        }
    }

    override fun delete(id: String) {
        cache.remove(id)
        dirty = true
        flusher.markDirty()
    }

    override fun findAll(): List<MemoryItem> {
        return cache.values.toList()
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
            log.debug("记忆已持久化到 $filePath（${cache.size} 条）")
        } catch (e: Exception) {
            log.error("记忆持久化失败: ${e.message}")
        }
    }

    /**
     * 从文件加载记忆
     */
    private fun load() {
        val file = File(filePath)
        if (!file.exists()) {
            log.info("记忆文件不存在，创建新的: $filePath")
            return
        }
        try {
            val memories: List<MemoryItem> = mapper.readValue(file)
            memories.forEach { cache[it.id] = it }
            log.info("已加载 ${memories.size} 条记忆")
        } catch (e: Exception) {
            log.error("记忆加载失败: ${e.message}")
        }
    }
}

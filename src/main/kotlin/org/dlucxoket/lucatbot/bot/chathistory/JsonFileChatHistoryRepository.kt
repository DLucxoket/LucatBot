package org.dlucxoket.lucatbot.bot.chathistory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.storage.DebouncedFlusher
import org.dlucxoket.lucatbot.bot.storage.JsonFiles
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * JSON 文件聊天记录仓库
 *
 * 存储格式：`data/chat-history.json`
 * 内存缓存 + 防抖落盘（写入后约 5s 批量 flush，进程退出兜底写盘）。
 */
class JsonFileChatHistoryRepository(
    private val filePath: String = "data/chat-history.json"
) : ChatHistoryRepository {

    private val log = LoggerFactory.getLogger(JsonFileChatHistoryRepository::class.java)
    private val mapper = jacksonObjectMapper()

    /** 记录缓存（id → record） */
    private val cache = ConcurrentHashMap<String, ChatMessageRecord>()

    @Volatile
    private var dirty = false

    /** 防抖落盘：save 后约 5s 批量写，shutdown 兜底 */
    private val flusher = DebouncedFlusher(
        flushAction = { doFlush() },
        delayMs = 5_000L,
        name = "chat-history-flush"
    )

    init {
        load()
    }

    override fun save(record: ChatMessageRecord) {
        cache[record.id] = record
        dirty = true
        flusher.markDirty()
    }

    override fun saveAll(records: List<ChatMessageRecord>) {
        records.forEach { cache[it.id] = it }
        dirty = true
        flusher.markDirty()
    }

    override fun findByConversation(conversationId: String, limit: Int, before: Long): List<ChatMessageRecord> {
        return cache.values
            .filter { it.conversationId == conversationId }
            .filter { before == 0L || it.timestamp < before }
            .sortedBy { it.timestamp }
            .let { if (limit > 0) it.takeLast(limit) else it }
    }

    override fun findUnread(conversationId: String): List<ChatMessageRecord> {
        return cache.values
            .filter { it.conversationId == conversationId && !it.isRead }
            .sortedBy { it.timestamp }
    }

    override fun findAllUnread(): List<ChatMessageRecord> {
        return cache.values
            .filter { !it.isRead }
            .sortedBy { it.timestamp }
    }

    override fun findUnreadConversations(): List<String> {
        return cache.values
            .filter { !it.isRead }
            .map { it.conversationId }
            .distinct()
    }

    override fun findBySender(senderId: Long): List<ChatMessageRecord> {
        return cache.values
            .filter { it.senderId == senderId }
            .sortedBy { it.timestamp }
    }

    override fun findById(id: String): ChatMessageRecord? {
        return cache[id]
    }

    override fun markAsRead(conversationId: String) {
        cache.values
            .filter { it.conversationId == conversationId && !it.isRead }
            .forEach { cache[it.id] = it.copy(isRead = true) }
        dirty = true
        flusher.markDirty()
    }

    override fun markOneAsRead(id: String) {
        cache[id]?.let {
            if (!it.isRead) {
                cache[id] = it.copy(isRead = true)
                dirty = true
                flusher.markDirty()
            }
        }
    }

    override fun delete(id: String) {
        cache.remove(id)
        dirty = true
        flusher.markDirty()
    }

    override fun cleanup(keepPerConversation: Int) {
        val byConv = cache.values.groupBy { it.conversationId }
        byConv.forEach { (_, records) ->
            if (records.size > keepPerConversation) {
                records.sortedByDescending { it.timestamp }
                    .drop(keepPerConversation)
                    .forEach { cache.remove(it.id) }
            }
        }
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
        // 先清脏标志再写：写入期间发生的新 save() 会重新置位，不会被误清后丢一轮落盘
        dirty = false
        try {
            JsonFiles.writeAtomic(mapper, File(filePath), cache.values.toList())
            log.debug("聊天记录已持久化（${cache.size} 条）")
        } catch (e: Exception) {
            dirty = true  // 写失败恢复标志，等待下次 flush / 退出兜底重试
            log.error("聊天记录持久化失败: ${e.message}")
        }
    }

    private fun load() {
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val records: List<ChatMessageRecord> = mapper.readValue(file)
            records.forEach { cache[it.id] = it }
            log.info("已加载 ${records.size} 条聊天记录")
        } catch (e: Exception) {
            // 加载失败绝不能让空缓存覆盖原文件：先改名备份，再以空缓存启动
            JsonFiles.backupCorrupt(file)
            log.error("聊天记录加载失败（${e.message}）：以空缓存启动，原文件已备份为 .corrupt-*，不会被覆盖")
        }
    }
}

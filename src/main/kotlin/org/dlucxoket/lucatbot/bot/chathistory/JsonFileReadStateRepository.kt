package org.dlucxoket.lucatbot.bot.chathistory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.storage.JsonFiles
import org.slf4j.LoggerFactory
import java.io.File

/**
 * JSON 文件已读水位仓库
 *
 * 存储格式：`data/read-state.json`（`{conversationId: epochMs}` 映射）。
 * 写入即落盘（水位只在进会话时写，低频；20 行级小文件原子写，无防抖需要）。
 */
class JsonFileReadStateRepository(
    private val filePath: String = "data/read-state.json"
) : ReadStateRepository {

    private val log = LoggerFactory.getLogger(JsonFileReadStateRepository::class.java)
    private val mapper = jacksonObjectMapper()

    /** 水位缓存（conversationId → epoch ms） */
    private val state = LinkedHashMap<String, Long>()

    init {
        load()
    }

    override fun lastReadAt(conversationId: String): Long? = state[conversationId]

    override fun markRead(conversationId: String, atMs: Long) {
        if (state[conversationId] == atMs) return  // 无变化不落盘
        state[conversationId] = atMs
        try {
            JsonFiles.writeAtomic(mapper, File(filePath), state)
        } catch (e: Exception) {
            log.error("已读水位持久化失败: ${e.message}")
        }
    }

    override fun allLastRead(): Map<String, Long> = state.toMap()

    /** StorageConfig destroyMethod 统一约定（本实现写入即落盘，无需刷盘，留空实现） */
    fun shutdown() {
        // no-op
    }

    private fun load() {
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val loaded: Map<String, Long> = mapper.readValue(file)
            state.putAll(loaded)
            log.info("已加载 ${loaded.size} 个会话的已读水位")
        } catch (e: Exception) {
            // 加载失败绝不让空状态覆盖原文件：改名备份，以空状态启动
            JsonFiles.backupCorrupt(file)
            log.error("已读水位加载失败（${e.message}）：以空状态启动，原文件已备份")
        }
    }
}

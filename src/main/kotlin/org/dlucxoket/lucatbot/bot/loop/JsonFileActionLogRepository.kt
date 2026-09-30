package org.dlucxoket.lucatbot.bot.loop

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.storage.JsonFiles
import org.slf4j.LoggerFactory
import java.io.File

/**
 * JSON 文件操作日志仓库
 *
 * 存储格式：`data/action-log.json`（`List<ActionLogEntry>`，整窗原子写）。
 * 旧版 List<String> 行格式反序列化失败 → 备份降级（以空启动，日志本就可重建）。
 */
class JsonFileActionLogRepository(
    private val filePath: String = "data/action-log.json"
) : ActionLogRepository {

    private val log = LoggerFactory.getLogger(JsonFileActionLogRepository::class.java)
    private val mapper = jacksonObjectMapper()

    override fun loadEntries(): List<ActionLogEntry> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()
        return try {
            mapper.readValue(file)
        } catch (e: Exception) {
            JsonFiles.backupCorrupt(file)
            log.error("操作日志加载失败（${e.message}）：以空启动，原文件已备份")
            emptyList()
        }
    }

    override fun saveEntries(entries: List<ActionLogEntry>) {
        try {
            JsonFiles.writeAtomic(mapper, File(filePath), entries)
            log.debug("操作日志已持久化（{} 条）", entries.size)
        } catch (e: Exception) {
            log.error("操作日志持久化失败: ${e.message}")
        }
    }

    /** Spring `destroyMethod` 钩子（与 MySQL 实现对齐；JSON 版无需释放资源）。 */
    fun shutdown() {}
}

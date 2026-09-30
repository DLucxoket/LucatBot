package org.dlucxoket.lucatbot.bot.loop

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.storage.JsonFiles
import org.slf4j.LoggerFactory
import java.io.File

/**
 * JSON 文件操作日志仓库
 *
 * 存储格式：`data/action-log.json`（`List<ActionLogEntry>`，原子写）。
 * 旧版 List<String> 行格式反序列化失败 → 备份降级（以空启动，日志本就可重建）。
 *
 * **写入语义 = 追加增量**（[appendEntries]）：读文件 → 拼上新增 → 裁剪到
 * [maxLines] 窗口 → 原子写。文件不是审计库，只保留最近窗口不无限膨胀
 * （要全量历史走 mysql 后端——那边只增不删、查询时 LIMIT）。
 */
class JsonFileActionLogRepository(
    private val filePath: String = "data/action-log.json",
    /** 文件里保留的窗口上限（与 ActionLog.maxLines 对齐，只留最近的） */
    private val maxLines: Int = 20
) : ActionLogRepository {

    private val log = LoggerFactory.getLogger(JsonFileActionLogRepository::class.java)
    private val mapper = jacksonObjectMapper()

    override fun loadEntries(): List<ActionLogEntry> = readAll().takeLast(maxLines)

    override fun appendEntries(newEntries: List<ActionLogEntry>) {
        if (newEntries.isEmpty()) return
        try {
            val merged = (readAll() + newEntries).takeLast(maxLines)
            JsonFiles.writeAtomic(mapper, File(filePath), merged)
            log.debug("操作日志已持久化（新增 {} 条 → 窗口 {} 条）", newEntries.size, merged.size)
        } catch (e: Exception) {
            log.error("操作日志持久化失败: ${e.message}")
        }
    }

    /** 读整个文件（不存在 → 空；损坏 → 备份降级为空，不覆盖原文件） */
    private fun readAll(): List<ActionLogEntry> {
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

    /** StorageConfig 的 destroyMethod 兜底；文件写是原子的，无需额外收尾 */
    fun shutdown() {}
}

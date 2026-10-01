package org.dlucxoket.lucatbot.bot.memory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.storage.JsonFiles
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 固定短记的一次变更——**完全覆盖式**更新
 *
 * 内容整体替换旧的（不是追加）；历史行**全部保留**，即「每次更改都把新内容
 * 记录进数据库」的审计轨迹，最新一条 = 当前内容。
 */
data class FixedMemoryChange(
    val content: String,
    val changedAt: Long = System.currentTimeMillis()
)

/** 固定短记仓库（存储抽象；DIP：按 storage.type 绑定实现） */
interface FixedMemoryRepository {
    /** 变更历史（时间升序；最后一条 = 当前内容；空 = 从未写过） */
    fun loadRecent(limit: Int = 100): List<FixedMemoryChange>

    /** 记一次新变更（**只追加，不删旧行**） */
    fun append(change: FixedMemoryChange)

    /** destroyMethod 兜底（json 实现无待写数据时 no-op） */
    fun shutdown()
}

/**
 * 固定短记（**固定放进决策 prompt 的短期记忆**）
 *
 * 与「记忆概览」不同：概览是全量记忆的动态窗口，这份是一段**小抄式**的
 * 固定文本（当前状态 / 约定 / 紧要事），每轮原样注入，由**退出会话时的
 * 记忆整理**（MemoryAgent 的 `fixed_memory` 输出）整体覆盖更新，
 * 不需要更新就不写字段（旧内容原样保留）。
 *
 * 持久化 + 每次更改入库：[repository] 追加一条 [FixedMemoryChange]，
 * 历史行即变更记录（mysql `fixed_memory` 表 / data/fixed-memory.json）；
 * repository=null 时纯内存（测试/默认）。
 */
class FixedMemory(
    private val repository: FixedMemoryRepository? = null
) {
    private val log = LoggerFactory.getLogger(FixedMemory::class.java)

    /** 当前内容（启动从仓库恢复最新一条；无 → 空串） */
    @Volatile
    private var current: String = ""

    init {
        current = repository?.let { repo ->
            runCatching { repo.loadRecent().lastOrNull()?.content.orEmpty() }
                .onFailure { e -> log.warn("固定短记恢复失败（以空启动）: {}", e.message) }
                .getOrNull().orEmpty()
        } ?: ""
    }

    /** 当前固定短记（空串 = 还没写过） */
    fun current(): String = current

    /**
     * 整体覆盖为 [content]（**每次生效的更改都追加入库**）
     *
     * @return true = 内容有变化并已记录；空内容 / 与当前相同 → 不动（= 不更新）
     */
    fun update(content: String): Boolean {
        val new = content.trim()
        if (new.isEmpty() || new == current) return false
        repository?.append(FixedMemoryChange(new))
        current = new
        log.info("固定短记已更新（{} 字）", new.length)
        return true
    }
}

/**
 * JSON 文件实现：`data/fixed-memory.json`（`List<FixedMemoryChange>`，原子写）
 *
 * 只追加不删（保存时裁到 [keepLines] 条防文件无限膨胀——审计意义在 mysql 后端）。
 */
class JsonFileFixedMemoryRepository(
    private val filePath: String = "data/fixed-memory.json",
    private val keepLines: Int = 100
) : FixedMemoryRepository {

    private val log = LoggerFactory.getLogger(JsonFileFixedMemoryRepository::class.java)
    private val mapper = jacksonObjectMapper()

    override fun loadRecent(limit: Int): List<FixedMemoryChange> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()
        return try {
            mapper.readValue<List<FixedMemoryChange>>(file).takeLast(limit)
        } catch (e: Exception) {
            JsonFiles.backupCorrupt(file)
            log.error("固定短记加载失败（${e.message}）：以空启动，原文件已备份")
            emptyList()
        }
    }

    override fun append(change: FixedMemoryChange) {
        try {
            val merged = (loadRecent(Int.MAX_VALUE) + change).takeLast(keepLines)
            JsonFiles.writeAtomic(mapper, File(filePath), merged)
        } catch (e: Exception) {
            log.error("固定短记持久化失败: ${e.message}")
        }
    }

    override fun shutdown() {
        // no-op：每次 append 即写盘（原子写）
    }
}

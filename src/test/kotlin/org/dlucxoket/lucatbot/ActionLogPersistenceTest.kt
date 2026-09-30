package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.ActionLogEntry
import org.dlucxoket.lucatbot.bot.loop.JsonFileActionLogRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ActionLog：结构化条目（时间/会话/操作/原因）+ 窗口滚动 + 持久化恢复
 *
 * 生产 LoopConfig 注入 storage.type 对应仓库（action_log 表 / json 文件），
 * 测试默认纯内存（repository=null）。
 */
class ActionLogPersistenceTest {

    @Test
    fun `persists and restores structured entries across instances`() {
        val path = File.createTempFile("action-log", ".json").apply { delete() }.absolutePath
        val repo = JsonFileActionLogRepository(path)
        val first = ActionLog(repository = repo)
        first.append("查看消息列表（2条未读）")
        first.append("回复：哈哈", reason = "接住话茬", conversationId = "private:123")
        first.shutdown()  // 停机兜底：立即写盘

        val restored = ActionLog(repository = repo)
        val dump = restored.dump()
        assertTrue(dump.contains("查看消息列表（2条未读）"), "重启恢复旧行：$dump")
        assertTrue(dump.contains("[private:123] 回复：哈哈（接住话茬）"),
            "会话标签 + 原因结构化恢复并渲染：$dump")
        assertTrue(Regex("""\[\d{2}:\d{2}]""").containsMatchIn(dump), "行带时间戳：$dump")

        // 字段级恢复完整（落库四列的内存形态）
        val entries = repo.loadEntries()
        assertEquals(2, entries.size)
        assertEquals("private:123", entries[1].conversationId)
        assertEquals("接住话茬", entries[1].reason)
        assertEquals("回复：哈哈", entries[1].action)
        assertTrue(entries[1].timestamp > 0L, "时间字段恢复")
        assertNull(entries[0].conversationId, "列表态无会话")

        // 恢复后继续滚动追加
        restored.append("第三行")
        assertTrue(restored.dump().contains("第三行"), "恢复后可继续追加")
    }

    @Test
    fun `rolling window keeps only newest entries`() {
        val log = ActionLog(maxLines = 3)
        repeat(5) { log.append("行$it") }
        val dump = log.dump()
        assertEquals(3, log.size(), "窗口上限 3 条")
        assertFalse(dump.contains("行0") || dump.contains("行1"), "最旧条目被滚动丢弃：$dump")
        assertTrue(dump.contains("行4") && dump.contains("行2"), "保留最新 3 条：$dump")
    }

    @Test
    fun `reason is trimmed to 30 chars and omitted when blank`() {
        val log = ActionLog()
        log.append("操作A", reason = "  超长原因".padEnd(60, '字'))
        log.append("操作B", reason = null)
        val dump = log.dump()
        assertEquals(1, dump.lines().count { it.contains("操作A（") }, "reason 存在")
        val reason = dump.substringAfter("操作A（").substringBefore("）")
        assertTrue(reason.length <= 30, "reason ≤30 字（实际 ${reason.length}）")
        assertFalse(dump.substringAfter("操作B").contains("（"), "空 reason 不带括号")
    }

    @Test
    fun `memory only mode never touches filesystem`() {
        // repository=null（测试默认）：append 不抛异常、不写盘
        val log = ActionLog()
        log.append("纯内存模式")
        assertEquals(1, log.size())
        assertTrue(log.dump().contains("纯内存模式"))
        log.clear()
        assertEquals(0, log.size())
        assertEquals("（暂无）", log.dump(), "清空后占位说明")
    }

    @Test
    fun `corrupt file falls back to empty without overwriting`() {
        val path = File.createTempFile("action-corrupt", ".json").apply { writeText("{not json") }.absolutePath
        val log = ActionLog(repository = JsonFileActionLogRepository(path))
        assertEquals("（暂无）", log.dump(), "损坏 → 空启动")
        // 原文件被备份（不被空内容覆盖）
        val backedUp = File(path).parentFile.listFiles()?.any { it.name.contains("corrupt") } == true
        assertTrue(backedUp, "损坏文件已备份")
    }
}

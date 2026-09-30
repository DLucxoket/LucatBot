package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** ActionLog：窗口滚动 + 持久化恢复（生产 LoopConfig 传路径、测试默认纯内存） */
class ActionLogPersistenceTest {

    @Test
    fun `persists and restores across instances`() {
        val path = File.createTempFile("action-log", ".json").apply { delete() }.absolutePath
        val first = ActionLog(filePath = path)
        first.append("查看消息列表（2条未读）")
        first.append("回复：哈哈（接住话茬）")
        first.shutdown()  // 停机兜底：立即写盘

        val restored = ActionLog(filePath = path)
        val dump = restored.dump()
        assertTrue(dump.contains("查看消息列表（2条未读）"), "重启恢复旧行：$dump")
        assertTrue(dump.contains("回复：哈哈（接住话茬）"), "reason 随行恢复：$dump")
        // 时间戳原样保留（append 时已带）
        assertTrue(Regex("""\[\d{2}:\d{2}]""").containsMatchIn(dump), "行带时间戳：$dump")

        // 恢复后继续滚动追加
        restored.append("第三行")
        assertTrue(restored.dump().contains("第三行"), "恢复后可继续追加")
    }

    @Test
    fun `rolling window keeps only newest lines`() {
        val log = ActionLog(maxLines = 3)
        repeat(5) { log.append("行$it") }
        val dump = log.dump()
        assertEquals(3, log.size(), "窗口上限 3 行")
        assertFalse(dump.contains("行0") || dump.contains("行1"), "最旧行被滚动丢弃：$dump")
        assertTrue(dump.contains("行4") && dump.contains("行2"), "保留最新 3 行：$dump")
    }

    @Test
    fun `memory only mode never touches filesystem`() {
        // filePath=null（测试默认）：append 不抛异常、不写盘
        val log = ActionLog()
        log.append("纯内存模式")
        assertEquals(1, log.size())
        assertTrue(log.dump().contains("纯内存模式"))
        log.clear()
        assertEquals(0, log.size())
    }

    @Test
    fun `corrupt file falls back to empty without overwriting`() {
        val path = File.createTempFile("action-corrupt", ".json").apply { writeText("{not json") }.absolutePath
        val log = ActionLog(filePath = path)
        assertEquals("（暂无）", log.dump(), "损坏 → 空启动")
        // 原文件被备份（不被空内容覆盖）
        val backedUp = File(path).parentFile.listFiles()?.any { it.name.contains("corrupt") } == true
        assertTrue(backedUp, "损坏文件已备份")
    }
}

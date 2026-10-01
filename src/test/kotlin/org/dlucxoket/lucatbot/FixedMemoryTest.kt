package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.memory.FixedMemory
import org.dlucxoket.lucatbot.bot.memory.FixedMemoryChange
import org.dlucxoket.lucatbot.bot.memory.FixedMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.JsonFileFixedMemoryRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 固定短记：整体覆盖更新 + 每次更改都入库（历史行 = 变更审计）
 */
class FixedMemoryTest {

    private class FakeRepo : FixedMemoryRepository {
        val history = mutableListOf<FixedMemoryChange>()
        override fun loadRecent(limit: Int): List<FixedMemoryChange> = history.takeLast(limit)
        override fun append(change: FixedMemoryChange) {
            history += change
        }
        override fun shutdown() {}
    }

    @Test
    fun `update overwrites current and records every change`() {
        val repo = FakeRepo()
        val fm = FixedMemory(repo)

        assertTrue(fm.update("在办黄皮耗子群的事"))
        assertTrue(fm.update("已办完；现在盯周五球局"))

        assertEquals("已办完；现在盯周五球局", fm.current(), "当前内容整体覆盖")
        assertEquals(
            listOf("在办黄皮耗子群的事", "已办完；现在盯周五球局"),
            repo.history.map { it.content },
            "每次更改的新内容都入库（旧行保留）"
        )
    }

    @Test
    fun `blank or unchanged update does not record`() {
        val repo = FakeRepo()
        val fm = FixedMemory(repo)

        fm.update("内容")
        assertFalse(fm.update("   "), "空内容 = 不更新")
        assertFalse(fm.update("内容"), "与当前相同 = 不更新（也就不必再记一行）")
        assertEquals(1, repo.history.size, "只记真正生效的更改")
    }

    @Test
    fun `restores latest entry from repository`() {
        val repo = FakeRepo()
        repo.history += FixedMemoryChange("旧内容")
        repo.history += FixedMemoryChange("新内容")

        assertEquals("新内容", FixedMemory(repo).current(), "启动恢复最新一条")
    }

    @Test
    fun `json file keeps change history across instances`() {
        val path = File.createTempFile("fixed-mem", ".json").apply { delete() }.absolutePath

        FixedMemory(JsonFileFixedMemoryRepository(path)).update("第一条")
        FixedMemory(JsonFileFixedMemoryRepository(path)).update("第二条")

        val history = JsonFileFixedMemoryRepository(path).loadRecent()
        assertEquals(listOf("第一条", "第二条"), history.map { it.content },
            "每条更改都留痕、跨实例可查")
        assertEquals("第二条", FixedMemory(JsonFileFixedMemoryRepository(path)).current(),
            "重启恢复当前内容")
    }

    @Test
    fun `memory only mode without repository works in memory`() {
        val fm = FixedMemory()
        assertTrue(fm.update("纯内存"))
        assertEquals("纯内存", fm.current())
        assertFalse(fm.update("纯内存"))
    }
}

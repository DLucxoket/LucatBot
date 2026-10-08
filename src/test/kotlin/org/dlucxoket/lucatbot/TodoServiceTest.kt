package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoItem
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** 待办清单：新增、完成匹配与摘要输出 */
class TodoServiceTest {

    private fun tempPath(): String =
        File.createTempFile("todos", ".json").apply { delete() }.absolutePath

    @Test
    fun `add dedupes same pending content`() {
        val repo = JsonFileTodoRepository(tempPath())
        val service = TodoService(repo)

        val first = service.add("帮他看群")
        val second = service.add("帮他看群")

        assertEquals(first.id, second.id, "相同 content 不应重复添加")
        assertEquals(1, service.pending().size)
        repo.shutdown()
    }

    @Test
    fun `add keeps distinct content and conversationId`() {
        val repo = JsonFileTodoRepository(tempPath())
        val service = TodoService(repo)

        val a = service.add("帮他看群", "group:42")
        val b = service.add("提醒他交房租")

        assertEquals(2, service.pending().size)
        assertEquals("group:42", a.conversationId)
        assertNull(b.conversationId)
        repo.shutdown()
    }

    @Test
    fun `complete by exact id`() {
        val repo = JsonFileTodoRepository(tempPath())
        val service = TodoService(repo)

        val item = service.add("帮他看群")
        assertTrue(service.complete(item.id))
        assertTrue(service.pending().isEmpty(), "完成后不应还在 pending")
        assertTrue(repo.findAll().single().done, "应标记 done 而非删除")
        repo.shutdown()
    }

    @Test
    fun `complete by content fragment match`() {
        val repo = JsonFileTodoRepository(tempPath())
        val service = TodoService(repo)

        service.add("帮他看群")
        assertTrue(service.complete("看群"), "按内容包含应能匹配")
        assertTrue(service.pending().isEmpty())
        assertFalse(service.complete("看群"), "已完成后不应再次匹配")
        repo.shutdown()
    }

    @Test
    fun `complete unknown returns false`() {
        val repo = JsonFileTodoRepository(tempPath())
        val service = TodoService(repo)

        service.add("帮他看群")
        assertFalse(service.complete("不存在的待办"))
        assertFalse(service.complete(""))
        assertEquals(1, service.pending().size)
        repo.shutdown()
    }

    @Test
    fun `pendingSummary format and empty placeholder`() {
        val repo = JsonFileTodoRepository(tempPath())
        val service = TodoService(repo)

        assertEquals("（无待办）", service.pendingSummary())

        val item = service.add("帮他看群")
        assertEquals(
            "- [ ] (${item.id.take(4)}) 帮他看群（${org.dlucxoket.lucatbot.bot.persona.TimeFormat.monthDay(item.createdAt)} 建）",
            service.pendingSummary(),
            "行尾带**创建时间**（没设预计就只有建的日期）"
        )

        service.add("提醒他交房租")
        val multi = service.pendingSummary()
        assertTrue(multi.contains("\n"))
        assertEquals(2, multi.lines().size)
        repo.shutdown()
    }

    @Test
    fun `planned time can be set cleared and shows with created time in summary`() {
        val repo = JsonFileTodoRepository(tempPath())
        val service = TodoService(repo)

        val planned = System.currentTimeMillis() + 3 * 3_600_000L
        val item = service.add("提醒他交房租", plannedAt = planned)
        assertEquals(planned, item.plannedAt, "新增即带预计执行时间")
        val line = service.pendingSummary()
        assertTrue(line.contains("建"), "创建时间进 prompt：$line")
        assertTrue(line.contains("预计"), "预计执行时间进 prompt：$line")

        // 同内容去重，但会把新的预计时间补上（别把计划丢了）
        val again = service.add("提醒他交房租", plannedAt = planned + 60_000L)
        assertEquals(item.id, again.id, "同内容不重复加")
        assertEquals(planned + 60_000L, again.plannedAt, "去重时补上新计划")

        // 面板路径：按 id 前缀设置 / 清除
        val set = service.setPlannedAt(item.id.take(4), planned)
        assertNotNull(set)
        assertEquals(planned, set!!.plannedAt)
        val cleared = service.setPlannedAt(item.id, null)
        assertNotNull(cleared)
        assertNull(cleared!!.plannedAt, "null = 清除预计")
        assertFalse(service.pendingSummary().contains("预计"), "清掉后不再展示预计")
        assertNull(service.setPlannedAt("不存在的id", planned), "无匹配 → null（幂等）")

        // 取消（与本批同属待办操作）
        assertEquals(item.id, service.cancel(item.id.take(4))?.id, "按前缀取消")
        assertTrue(service.pending().isEmpty(), "取消后从清单消失")
        repo.shutdown()
    }

    @Test
    fun `complete by id prefix and summaryForPrompt shows recent done`() {
        // 实盘：只展示 id 前 4 位 → LLM 照抄前缀；且办完的事必须在 prompt 里看得见
        val repo = JsonFileTodoRepository(tempPath())
        val service = TodoService(repo)

        val item = service.add("帮他看群")
        assertTrue(service.complete(item.id.take(4)), "按 id 前缀应能匹配完成")
        assertTrue(service.pending().isEmpty())

        val summary = service.summaryForPrompt()
        assertTrue(summary.contains("（无待办）"), "未完成段空占位：$summary")
        assertTrue(summary.contains("最近已完成"), "完成段标题：$summary")
        assertTrue(summary.contains("- [x]") && summary.contains("帮他看群"),
            "完成的事也看得见：$summary")
        repo.shutdown()
    }

    @Test
    fun `summaryForPrompt is pendingSummary when nothing done`() {
        val repo = JsonFileTodoRepository(tempPath())
        val service = TodoService(repo)
        service.add("帮他看群")
        assertEquals(service.pendingSummary(), service.summaryForPrompt(), "没有完成项时不加尾巴")
        repo.shutdown()
    }

    @Test
    fun `json repo write flush and reload in new instance`() {
        val path = tempPath()

        val repo1 = JsonFileTodoRepository(path)
        repo1.save(TodoItem(content = "帮他看群", conversationId = "group:42"))
        repo1.save(TodoItem(content = "已存在的旧待办", done = true))
        repo1.flush()
        repo1.shutdown()

        val repo2 = JsonFileTodoRepository(path)
        val loaded = repo2.findAll()
        assertEquals(2, loaded.size, "新实例应能读到已落盘的待办")

        val pending = loaded.filter { !it.done }
        assertEquals(1, pending.size)
        assertEquals("帮他看群", pending.single().content)
        assertEquals("group:42", pending.single().conversationId)
        assertTrue(loaded.any { it.done })
        repo2.shutdown()
    }
}

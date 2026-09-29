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
        assertEquals("- [ ] (${item.id.take(4)}) 帮他看群", service.pendingSummary())

        service.add("提醒他交房租")
        val multi = service.pendingSummary()
        assertTrue(multi.contains("\n"))
        assertEquals(2, multi.lines().size)
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

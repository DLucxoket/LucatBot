package org.dlucxoket.lucatbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.storage.DebouncedFlusher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 持久化安全：损坏文件备份不被覆盖、防抖落盘有界延迟 */
class StorageSafetyTest {

    @Test
    fun `corrupt file is backed up and never overwritten`() {
        val file = File.createTempFile("mem-corrupt", ".json")
        file.writeText("{ 这不是合法 JSON !!!")

        // 构造时 load 失败 → 原文件应被改名备份，以空缓存启动
        val repo = JsonFileMemoryRepository(file.absolutePath)
        val backups = file.parentFile.listFiles { f -> f.name.startsWith(file.name + ".corrupt-") }
        assertFalse(backups.isNullOrEmpty(), "损坏文件应备份为 .corrupt-*")
        assertEquals("{ 这不是合法 JSON !!!", backups!![0].readText(), "备份必须保留原始内容")

        // 后续保存 + flush 写出合法新文件，且不触碰备份
        repo.save(MemoryItem(userId = 1L, content = "新记忆"))
        repo.flush()

        val loaded: List<MemoryItem> = jacksonObjectMapper().readValue(file)
        assertEquals(1, loaded.size, "新文件应是合法的完整列表")
        assertEquals("{ 这不是合法 JSON !!!", backups[0].readText(), "备份不能被 flush 覆盖")
    }

    @Test
    fun `flusher fires despite continuous writes bounded by maxWait`() {
        val flushes = AtomicInteger(0)
        // delay=10s（防抖窗口很宽）、maxWait=200ms：持续写入必须在 200ms 后强制落盘
        val flusher = DebouncedFlusher(
            flushAction = { flushes.incrementAndGet() },
            delayMs = 10_000L,
            maxWaitMs = 200L,
            name = "test-flush"
        )
        try {
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline && flushes.get() == 0) {
                flusher.markDirty()
                Thread.sleep(20)
            }
            assertTrue(flushes.get() >= 1, "持续写入下 maxWait 内必须发生一次落盘（防饥饿）")
        } finally {
            flusher.shutdown()
        }
    }

    @Test
    fun `shutdown flushes pending changes`() {
        val flushes = AtomicInteger(0)
        val flusher = DebouncedFlusher(
            flushAction = { flushes.incrementAndGet() },
            delayMs = 10_000L,
            maxWaitMs = 60_000L,
            name = "test-shutdown"
        )
        flusher.markDirty()
        assertEquals(0, flushes.get(), "防抖窗口内不应提前落盘")
        flusher.shutdown()
        assertEquals(1, flushes.get(), "shutdown 必须兜底落盘一次")
    }
}

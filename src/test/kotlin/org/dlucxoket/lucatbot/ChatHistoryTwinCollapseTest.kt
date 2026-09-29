package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.model.event.message.FriendMessageEvent
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.dlucxoket.lucatbot.model.user.Sex
import org.dlucxoket.lucatbot.model.user.User
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 聊天记录 id 统一为 `msg:` 风格 + 孪生双份行的启动清理
 *
 * - 爬楼带 message_id → 与实时入档同用稳定 id `msg:<会话>:<id>`（同一主键 upsert 合一）
 * - 无 id 的存量/回退行（hist:/UUID）：读取 fold 兜底 + 启动 collapse 收敛到 msg: 行
 * - 真实连发（两个不同 msg: 行 / 两个 UUID 行）不折叠
 */
class ChatHistoryTwinCollapseTest {

    private class Fixture {
        val repo = JsonFileChatHistoryRepository(File.createTempFile("hist-twin", ".json").absolutePath)
        val service = ChatHistoryService(repo)
    }

    private fun fixture() = Fixture()

    private fun liveEvent(messageId: Long?, content: String = "你好", ts: Long = 5_000L) = FriendMessageEvent(
        message = ChatMessage(listOf(TextMessageMember(content))),
        sender = User(10001L, "小明", Sex.UNKNOWN, 0, ""),
        timestamp = ts,
        messageId = messageId
    )

    // ------------------------------------------------------------------
    // 爬楼 id 风格
    // ------------------------------------------------------------------

    @Test
    fun `crawl record with message id uses msg style id`() {
        val f = fixture()
        f.service.recordHistoryMessage(
            conversationId = "private:1", senderId = 10001L, senderName = "小明",
            content = "你好", timestamp = 5_000L, isFromBot = false, messageId = 42L
        )
        val raw = f.repo.findByConversation("private:1", 0)
        assertEquals(1, raw.size)
        assertEquals("msg:private:1:42", raw[0].id, "带 message_id 的爬楼记录用稳定 id")
        assertEquals(42L, raw[0].messageId)
    }

    @Test
    fun `crawl record without message id falls back to hist id`() {
        val f = fixture()
        f.service.recordHistoryMessage(
            conversationId = "private:1", senderId = 10001L, senderName = "小明",
            content = "你好", timestamp = 5_000L, isFromBot = false
        )
        assertTrue(f.repo.findByConversation("private:1", 0)[0].id.startsWith("hist:"), "无 id 落 hist: 兜底")
    }

    @Test
    fun `crawl with same message id as live row merges into one row`() {
        // 同一物理消息：实时入档（msg: id）先写、爬楼（同 message_id）后写 → 同主键 upsert 合一
        val f = fixture()
        f.service.recordMessage(liveEvent(messageId = 42L), "你好")
        f.service.recordHistoryMessage(
            conversationId = "private:1", senderId = 10001L, senderName = "小明",
            content = "你好", timestamp = 5_000L, isFromBot = false, messageId = 42L
        )
        val raw = f.repo.findByConversation("private:1", 0)
        assertEquals(1, raw.size, "同一 message_id 靠主键合一，不产生孪生双份")
        assertEquals(42L, raw[0].messageId)
    }

    // ------------------------------------------------------------------
    // 读取 fold（存量/无 id 行的兜底）
    // ------------------------------------------------------------------

    @Test
    fun `read folds uuid live row with msg crawl row keeping msg id`() {
        val f = fixture()
        // 无回执的实时行（UUID）× 带 id 的爬楼行：读时折叠，保权威 msg: id
        f.repo.save(
            ChatMessageRecord(
                conversationId = "group:1", senderId = 0L, senderName = "bot",
                content = "行行行", timestamp = 5_000L, isFromBot = true
            )
        )
        f.service.recordHistoryMessage(
            conversationId = "group:1", senderId = 0L, senderName = "bot",
            content = "行行行", timestamp = 5_100L, isFromBot = true, messageId = 77L
        )
        assertEquals(2, f.repo.findAll().size, "写入层不合并（无 id 猜测），存储由启动清理收敛")
        val history = f.service.getHistory("group:1", 10)
        assertEquals(1, history.size, "读取折叠为一条")
        assertTrue(history[0].id.startsWith("msg:"), "折叠保权威 id：${history[0].id}")
        assertEquals(77L, history[0].messageId)
    }

    @Test
    fun `read keeps two distinct msg rows as real repeats`() {
        val f = fixture()
        f.repo.save(
            ChatMessageRecord(
                id = "msg:group:1:1", conversationId = "group:1", senderId = 5L, senderName = "甲",
                content = "哈哈", timestamp = 5_000L, messageId = 1L
            )
        )
        f.repo.save(
            ChatMessageRecord(
                id = "msg:group:1:2", conversationId = "group:1", senderId = 5L, senderName = "甲",
                content = "哈哈", timestamp = 5_500L, messageId = 2L
            )
        )
        assertEquals(2, f.service.getHistory("group:1", 10).size, "两个不同 msg: 行 = 真实连发，不折叠")
    }

    // ------------------------------------------------------------------
    // 启动清理 collapseStoredTwins
    // ------------------------------------------------------------------

    @Test
    fun `collapse merges stored twins into single msg row`() {
        val f = fixture()
        // 存量三胞胎（截图形态）：CQ 版 hist 行 + 无回执 UUID 行 + 带 id 的行
        f.repo.save(
            ChatMessageRecord(
                id = "hist:group:1:5:4900:123", conversationId = "group:1", senderId = 5L, senderName = "甲",
                content = "[CQ:at,qq=1]你好", timestamp = 4_900L
            )
        )
        f.repo.save(
            ChatMessageRecord(
                conversationId = "group:1", senderId = 5L, senderName = "甲",
                content = "你好", timestamp = 5_000L
            )
        )
        f.repo.save(
            ChatMessageRecord(
                id = "msg:group:1:88", conversationId = "group:1", senderId = 5L, senderName = "甲",
                content = "你好", timestamp = 5_050L, messageId = 88L
            )
        )
        assertEquals(3, f.repo.findAll().size)

        f.service.collapseStoredTwins()

        val remaining = f.repo.findAll()
        assertEquals(1, remaining.size, "孪生双份收敛为一行")
        assertEquals("msg:group:1:88", remaining[0].id, "保权威 msg: id")
        assertEquals(88L, remaining[0].messageId, "messageId 合并保留")
        assertEquals("你好", remaining[0].content, "内容取干净版本（无 CQ）")
        assertEquals(1, f.service.getHistory("group:1", 10).size, "读取一致为一条")
    }

    @Test
    fun `collapse is idempotent and keeps real repeats`() {
        val f = fixture()
        f.repo.save(
            ChatMessageRecord(
                id = "msg:group:1:1", conversationId = "group:1", senderId = 5L, senderName = "甲",
                content = "哈哈", timestamp = 5_000L, messageId = 1L
            )
        )
        f.repo.save(
            ChatMessageRecord(
                id = "msg:group:1:2", conversationId = "group:1", senderId = 5L, senderName = "甲",
                content = "哈哈", timestamp = 5_500L, messageId = 2L
            )
        )
        f.repo.save(
            ChatMessageRecord(
                conversationId = "group:1", senderId = 9L, senderName = "乙",
                content = "你好", timestamp = 6_000L
            )
        )

        f.service.collapseStoredTwins()
        val afterFirst = f.repo.findAll().map { it.id to it.content }.toSet()
        f.service.collapseStoredTwins()
        val afterSecond = f.repo.findAll().map { it.id to it.content }.toSet()

        assertEquals(3, f.repo.findAll().size, "真实连发与不同发送者不被折叠")
        assertEquals(afterFirst, afterSecond, "清理幂等：二次执行无变化")
    }

    @Test
    fun `fold preserves memorized flag across twins`() {
        // 未打标的实时行（折叠时当 survivor）+ 已打标的爬楼孪生行 → 标记不能丢
        val f = fixture()
        f.repo.save(
            ChatMessageRecord(
                conversationId = "group:3", senderId = 5L, senderName = "甲",
                content = "他叫我老大", timestamp = 5_000L, memorized = false
            )
        )
        f.repo.save(
            ChatMessageRecord(
                id = "hist:group:3:5:5100:123", conversationId = "group:3",
                senderId = 5L, senderName = "甲",
                content = "他叫我老大", timestamp = 5_100L, memorized = true
            )
        )
        val history = f.service.getHistory("group:3", 10)
        assertEquals(1, history.size)
        assertTrue(history[0].memorized, "折叠 OR 合并保留已记忆标记")
        assertTrue(f.service.getHistorySummary("group:3", 10).contains("（已记忆）"))
    }

    @Test
    fun `collapse skips single row conversations untouched`() {
        val f = fixture()
        f.repo.save(
            ChatMessageRecord(
                conversationId = "group:9", senderId = 5L, senderName = "甲",
                content = "只有我一条", timestamp = 1_000L
            )
        )
        f.service.collapseStoredTwins()
        assertEquals(1, f.repo.findAll().size)
        assertEquals("只有我一条", f.repo.findAll()[0].content)
    }
}

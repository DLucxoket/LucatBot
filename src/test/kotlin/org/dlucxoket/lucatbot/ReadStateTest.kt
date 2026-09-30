package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileReadStateRepository
import org.dlucxoket.lucatbot.bot.readstate.ReadStateService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 已读水位（ReadStateService）：进会话写水位 + 重启按水位重建未读
 *
 * 此前未读纯内存重启即丢——水位化后「未读 = chat_history 里 time > 水位」。
 */
class ReadStateTest {

    private fun chatService(): ChatHistoryService = ChatHistoryService(
        JsonFileChatHistoryRepository(File.createTempFile("hist-read", ".json").absolutePath)
    )

    @Test
    fun `markRead advances watermark to last message time`() {
        val chat = chatService()
        val repo = JsonFileReadStateRepository(
            File.createTempFile("read-state", ".json").apply { delete() }.absolutePath
        )
        val svc = ReadStateService(chat, repo)

        chat.recordHistoryMessage("private:1", 9L, "甲", "第一条", timestamp = 1_000L, isFromBot = false)
        chat.recordHistoryMessage("private:1", 9L, "甲", "第二条", timestamp = 2_000L, isFromBot = false)
        svc.markRead("private:1")

        assertEquals(2_000L, repo.lastReadAt("private:1"), "水位 = 该会话最后一条消息时间")
        svc.markRead("group:2")  // 无记录会话 → 不写不动
        assertNull(repo.lastReadAt("group:2"), "无记录不写水位")
    }

    @Test
    fun `rebuild restores unread after restart with at flags`() {
        val chat = chatService()
        val repo = JsonFileReadStateRepository(
            File.createTempFile("read-state2", ".json").apply { delete() }.absolutePath
        )
        val svc = ReadStateService(chat, repo)
        val selfId = 3046673325L

        // 模拟重启前：三条消息、当时已读到第一条
        chat.recordHistoryMessage("private:7", 7L, "小明", "已读的", timestamp = 1_000L, isFromBot = false)
        chat.recordHistoryMessage(
            "private:7", 7L, "小明", "@3046673325 在吗", timestamp = 2_000L,
            isFromBot = false, messageId = 42L
        )
        chat.recordHistoryMessage("private:7", 7L, "小明", "还没看的", timestamp = 3_000L, isFromBot = false)
        svc.markRead("private:7")
        // 把水位拨回第一条（模拟：后两条是重启前没看的）
        repo.markRead("private:7", 1_000L)

        val bot = LucatBot()
        svc.rebuildUnread(bot, selfId)

        val unread = bot.peekUnread("private:7")
        assertEquals(2, unread.size, "水位之后的 2 条重建为未读")
        assertEquals(42L, unread[0].messageId, "message_id 恢复（引用可解析）")
        assertTrue(unread[0].isAtMe, "裸 @selfId 识别为 @我")
        assertFalse(unread[1].isAtMe, "普通消息不是 @我")

        // 幂等：再调不重复灌
        svc.rebuildUnread(bot, selfId)
        assertEquals(2, bot.peekUnread("private:7").size, "重建幂等")
    }

    @Test
    fun `rebuild skips when queue already has live unread`() {
        val chat = chatService()
        val repo = JsonFileReadStateRepository(
            File.createTempFile("read-state3", ".json").apply { delete() }.absolutePath
        )
        val svc = ReadStateService(chat, repo)
        chat.recordHistoryMessage("private:8", 8L, "乙", "重启前的", timestamp = 1_000L, isFromBot = false)
        repo.markRead("private:8", 0L)

        val bot = LucatBot()
        // 用真实入口造未读（handleMessage → addUnread；enqueuePendingInsert 是另一条队列）
        bot.backToConversationList()
        bot.handleMessage(
            org.dlucxoket.lucatbot.model.event.message.FriendMessageEvent(
                message = org.dlucxoket.lucatbot.model.message.ChatMessage(
                    listOf(org.dlucxoket.lucatbot.model.message.member.TextMessageMember("运行中到达的"))
                ),
                sender = org.dlucxoket.lucatbot.model.user.User(
                    8L, "乙", org.dlucxoket.lucatbot.model.user.Sex.UNKNOWN, 0, "", card = null
                ),
                timestamp = 5_000L, messageId = 7L
            ),
            isAtMe = false, isAtAll = false
        )
        svc.rebuildUnread(bot, 123L)

        val unread = bot.peekUnread("private:8")
        assertEquals(1, unread.size, "已有实时未读 → 重建结果不覆盖")
        assertEquals("运行中到达的", unread[0].content)
    }

    @Test
    fun `nickname at form also recognized as self`() {
        val chat = chatService()
        val repo = JsonFileReadStateRepository(
            File.createTempFile("read-state4", ".json").apply { delete() }.absolutePath
        )
        val svc = ReadStateService(chat, repo)
        chat.recordHistoryMessage(
            "private:9", 9L, "小明", "@牢洛(3046673325) 你来说", timestamp = 1_000L, isFromBot = false
        )
        repo.markRead("private:9", 0L)

        val bot = LucatBot()
        svc.rebuildUnread(bot, 3046673325L)

        assertTrue(bot.peekUnread("private:9").single().isAtMe, "@昵称(QQ) 形态识别 @我")
    }
}

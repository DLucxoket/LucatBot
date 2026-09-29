package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.ChatReferenceResolver
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.model.event.message.FriendMessageEvent
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.dlucxoket.lucatbot.model.user.Sex
import org.dlucxoket.lucatbot.model.user.User
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** 引用解析：@QQ → 昵称、回复 #id → 被回复消息（谁/何时/说了什么） */
class ChatReferenceResolverTest {

    private val fmt: (Long) -> String = { "14:02" }

    private fun entry(
        messageId: Long?,
        senderId: Long,
        sender: String,
        content: String,
        timestamp: Long = 1_000L
    ) = ChatReferenceResolver.WindowEntry(messageId, senderId, sender, timestamp, content)

    private val index = ChatReferenceResolver.indexOf(
        listOf(
            entry(111L, 10001L, "小明", "我最喜欢的颜色是蓝色"),
            entry(222L, 10002L, "小红", "看这个[图片 file=a.jpg]哈哈"),
            entry(null, 10003L, "阿强", "在吗")
        )
    )

    @Test
    fun `reply id resolves to sender time and excerpt`() {
        val out = ChatReferenceResolver.resolve("回复 #111：我也是", index, fmt)
        assertEquals("回复 @小明[14:02]：我最喜欢的颜色是蓝色我也是", out)
    }

    @Test
    fun `reply to unknown id keeps original`() {
        val out = ChatReferenceResolver.resolve("回复 #999：嗯嗯", index, fmt)
        assertEquals("回复 #999：嗯嗯", out, "窗口内查不到的 id 保持原样")
    }

    @Test
    fun `bare at resolves to nickname`() {
        val out = ChatReferenceResolver.resolve("@10003 在吗", index, fmt)
        assertEquals("@阿强(10003) 在吗", out)
    }

    @Test
    fun `at with nickname or unknown qq untouched`() {
        assertEquals("@小明(10001) 哈哈", ChatReferenceResolver.resolve("@小明(10001) 哈哈", index, fmt))
        assertEquals("@999999999 在吗", ChatReferenceResolver.resolve("@999999999 在吗", index, fmt))
        assertEquals("@全体成员 集合", ChatReferenceResolver.resolve("@全体成员 集合", index, fmt))
    }

    @Test
    fun `excerpt strips markers and truncates`() {
        // 被回复内容含图片占位符 → 摘录剥掉标记
        val out = ChatReferenceResolver.resolve("回复 #222：收到了", index, fmt)
        assertTrue(out.startsWith("回复 @小红[14:02]：看这个哈哈收到了"), out)

        // 超长摘录截断（> 30 字）
        val long = ChatReferenceResolver.indexOf(
            listOf(entry(333L, 1L, "某人", "一二三四五六七八九十".repeat(4)))
        )
        val out2 = ChatReferenceResolver.resolve("回复 #333：好", long, fmt)
        assertTrue(out2.contains("…"), out2)
        assertFalse(out2.contains("一二三四五六七八九十".repeat(4)), out2)
    }

    @Test
    fun `format time is injected by caller`() {
        val out = ChatReferenceResolver.resolve("回复 #111：ok", index) { "2024-05-01 14:02" }
        assertTrue(out.contains("[2024-05-01 14:02]"), out)
    }
}

/** message_id 入档：稳定 id、发送回执 id、读取折叠时的 id 合并 */
class HistoryMessageIdTest {

    private fun service() = ChatHistoryService(
        JsonFileChatHistoryRepository(File.createTempFile("hist-msgid", ".json").absolutePath)
    )

    private fun event(messageId: Long?, content: String = "hello", ts: Long = 1_000L) = FriendMessageEvent(
        message = ChatMessage(listOf(TextMessageMember(content))),
        sender = User(10001L, "小明", Sex.UNKNOWN, 0, ""),
        timestamp = ts,
        messageId = messageId
    )

    @Test
    fun `live record carries messageId and stable id`() {
        val s = service()
        s.recordMessage(event(messageId = 42L), "hello")
        val history = s.getHistory("private:10001", 10)
        assertEquals(1, history.size)
        assertEquals(42L, history[0].messageId)
        assertEquals("msg:private:10001:42", history[0].id, "带 id 的记录用稳定 id")
    }

    @Test
    fun `fold merges messageId onto kept record`() {
        val s = service()
        // 实时入档（有 id）+ 爬楼入档（hist: id、无 message_id，同一物理消息）
        s.recordMessage(event(messageId = 42L), "hello")
        s.recordHistoryMessage(
            conversationId = "private:10001", senderId = 10001L, senderName = "小明",
            content = "hello", timestamp = 1_000L, isFromBot = false
        )
        val history = s.getHistory("private:10001", 10)
        assertEquals(1, history.size, "同一物理消息折叠为一条")
        assertEquals(42L, history[0].messageId, "折叠时把 messageId 合并到保留记录")
    }

    @Test
    fun `bot reply with receipt id gets stable id`() {
        val s = service()
        s.recordBotReply("private:10001", "收到", messageId = 77L)
        val history = s.getHistory("private:10001", 10)
        assertEquals(1, history.size)
        assertEquals(77L, history[0].messageId)
        assertEquals("msg:private:10001:77", history[0].id)
    }
}

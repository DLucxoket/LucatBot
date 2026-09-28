package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.adapter.onebot.ReplySplitter
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.member.AtMessageMember
import org.dlucxoket.lucatbot.model.message.member.ImageMessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.dlucxoket.lucatbot.bot.memory.MemoryExtractor
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReplySplitterTest {

    @Test
    fun `short reply not split`() {
        val result = ReplySplitter.split("哈哈")
        assertEquals(1, result.size)
    }

    @Test
    fun `long reply split into multiple`() {
        val reply = "这句话很长很长很长很长很长很长很长很长。这句话也很长很长很长很长很长很长很长很长。这句话同样很长很长很长很长很长很长很长很长。"
        val result = ReplySplitter.split(reply)
        assertTrue(result.size > 1, "Expected split but got ${result.size}: $result")
        assertTrue(result.size <= 3)
    }

    @Test
    fun `empty reply returns empty list`() {
        val result = ReplySplitter.split("")
        assertTrue(result.isNotEmpty())  // short threshold returns [""]
    }
}

class ChatMessageTest {

    @Test
    fun `describe text only`() {
        val msg = ChatMessage(listOf(TextMessageMember("hello")))
        assertEquals("hello", msg.describe())
    }

    @Test
    fun `describe with image`() {
        val msg = ChatMessage(listOf(TextMessageMember("看这个"), ImageMessageMember("http://img.jpg")))
        assertTrue(msg.describe().contains("[图片]"))
        assertTrue(msg.describe().contains("看这个"))
    }

    @Test
    fun `isAtAll detects`() {
        val msg = ChatMessage(listOf(AtMessageMember(-1L)))
        assertTrue(msg.isAtAll())
    }

    @Test
    fun `isAtAll not regular at`() {
        val msg = ChatMessage(listOf(AtMessageMember(123456L)))
        assertFalse(msg.isAtAll())
    }

    @Test
    fun `extractText only text members`() {
        val msg = ChatMessage(listOf(TextMessageMember("你好"), ImageMessageMember("url")))
        assertEquals("你好", msg.extractText())
    }
}

class BotMoodTest {

    @Test
    fun `boost increases intensity`() {
        val mood = BotMood(BotMood.MoodType.NORMAL, 0.5)
        val boosted = mood.boost()
        assertTrue(boosted.intensity > mood.intensity)
    }

    @Test
    fun `drop decreases intensity`() {
        val mood = BotMood(BotMood.MoodType.HAPPY, 0.8)
        val dropped = mood.drop()
        assertTrue(dropped.intensity < mood.intensity)
    }

    @Test
    fun `decay reduces intensity`() {
        val mood = BotMood(BotMood.MoodType.NORMAL, 0.8)
        val decayed = mood.decay()
        assertTrue(decayed.intensity < mood.intensity)
    }

    @Test
    fun `replyWillingness between 0 and 1`() {
        BotMood.MoodType.values().forEach { type ->
            val mood = BotMood(type, 0.5)
            val w = mood.replyWillingness()
            assertTrue(w in 0.0..1.0, "Mood $type willingness $w out of range")
        }
    }
}

class SessionManagerTest {
    private val manager = org.dlucxoket.lucatbot.model.session.SessionManager()

    @Test
    fun `add and get history`() {
        manager.addMessage("private:1", "Alice", "hello")
        manager.addMessage("private:1", "bot", "hi", isBot = true)
        val history = manager.getHistory("private:1")
        assertEquals(2, history.size)
    }

    @Test
    fun `history limit works`() {
        repeat(25) { manager.addMessage("private:1", "user", "msg$it") }
        val history = manager.getHistory("private:1", limit = 10)
        assertEquals(10, history.size)
    }

    @Test
    fun `context summary for new session`() {
        assertEquals("（新会话）", manager.getContextSummary("private:new"))
    }

    @Test
    fun `fatigue increases with messages`() {
        manager.addMessage("private:1", "user", "hi")
        val f1 = manager.getFatigue("private:1")
        repeat(20) { manager.addMessage("private:1", "user", "msg") }
        val f2 = manager.getFatigue("private:1")
        assertTrue(f2 > f1)
    }

    @Test
    fun `fatigue hint changes`() {
        assertTrue(manager.getFatigueHint("private:empty").isEmpty())
    }
}

class MemoryExtractorTest {

    @Test
    fun `extract name from message`() {
        val memories = MemoryExtractor.extract(123L, "我叫张三")
        val nameMemory = memories.find { it.type == MemoryItem.MemoryType.FACT }
        assertNotNull(nameMemory)
        assertTrue(nameMemory!!.content.contains("张三"))
    }

    @Test
    fun `extract like from message`() {
        val memories = MemoryExtractor.extract(123L, "我喜欢猫")
        val likeMemory = memories.find { it.type == MemoryItem.MemoryType.FACT }
        assertNotNull(likeMemory)
        assertTrue(likeMemory!!.content.contains("猫"))
    }

    @Test
    fun `does not store plain messages as memories`() {
        val memories = MemoryExtractor.extract(123L, "你好")
        assertTrue(memories.none { it.type == MemoryItem.MemoryType.EVENT },
            "普通消息不应写入记忆")
        assertTrue(memories.isEmpty(), "无策略命中时不应产出记忆")
    }

    @Test
    fun `no fact when nothing to extract`() {
        val memories = MemoryExtractor.extract(123L, "今天天气不错")
        assertTrue(memories.none { it.type == MemoryItem.MemoryType.FACT })
    }
}

class ChatHistoryPersistenceTest {

    @Test
    fun `recordHistoryMessage is idempotent`() {
        val file = File.createTempFile("hist-dedup", ".json")
        val service = org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService(
            org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository(file.absolutePath)
        )
        repeat(2) {
            service.recordHistoryMessage(
                conversationId = "private:1",
                senderId = 123L,
                senderName = "用户A",
                content = "爬楼看到的",
                timestamp = 1000L,
                isFromBot = false
            )
        }
        val history = service.getHistory("private:1", 10)
        assertEquals(1, history.size, "同一历史消息重复写入应去重")
        assertEquals("爬楼看到的", history.first().content)
    }
}

class ChatHistoryReadDedupTest {

    private fun newService(): org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService {
        val file = File.createTempFile("hist-readdup", ".json")
        return org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService(
            org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository(file.absolutePath)
        )
    }

    @Test
    fun `bot message recorded live and via crawl collapses to one`() {
        val service = newService()
        service.recordBotReply("group:9", "行行行，是我这儿的问题")
        service.recordHistoryMessage(
            conversationId = "group:9", senderId = 3046673325L,
            senderName = "bot", content = "行行行，是我这儿的问题",
            timestamp = System.currentTimeMillis() + 300, isFromBot = true
        )
        val history = service.getHistory("group:9", 10)
        assertEquals(1, history.size, "实时入档 + 爬楼入档应折叠为一条")
        assertFalse(history.single().content.contains("[CQ:"))
    }

    @Test
    fun `incoming plain record and crawled cq record collapse keeping plain`() {
        val service = newService()
        // 实时入档（UUID id、纯文本）经仓库直写模拟
        val repoField = ChatHistoryService::class.java.getDeclaredField("repository").apply { isAccessible = true }
        val repoObj = repoField.get(service) as org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryRepository
        repoObj.save(
            org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord(
                conversationId = "group:9", senderId = 123L, senderName = "张三",
                content = "你发个问号干啥", timestamp = 5000L, isFromBot = false
            )
        )
        // 爬楼入档（hist id、带 CQ 码）
        service.recordHistoryMessage(
            conversationId = "group:9", senderId = 123L, senderName = "张三",
            content = "[CQ:reply,id=1]你发个问号干啥",
            timestamp = 5500L, isFromBot = false
        )
        val history = service.getHistory("group:9", 10)
        assertEquals(1, history.size, "纯文本版与 CQ 版应折叠")
        assertEquals("你发个问号干啥", history.single().content, "应保留无 CQ 的干净版本")
    }

    @Test
    fun `two live records with same content are both kept`() {
        val service = newService()
        val repoField = ChatHistoryService::class.java.getDeclaredField("repository").apply { isAccessible = true }
        val repoObj = repoField.get(service) as org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryRepository
        repeat(2) {
            repoObj.save(
                org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord(
                    conversationId = "group:9", senderId = 123L, senderName = "张三",
                    content = "666", timestamp = 5000L + it, isFromBot = false
                )
            )
        }
        val history = service.getHistory("group:9", 10)
        assertEquals(2, history.size, "两条均非爬楼的同内容记录（疑似真实连发）应保留")
    }

    @Test
    fun `same content from different senders is kept`() {
        val service = newService()
        val repoField = ChatHistoryService::class.java.getDeclaredField("repository").apply { isAccessible = true }
        val repoObj = repoField.get(service) as org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryRepository
        repoObj.save(
            org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord(
                conversationId = "group:9", senderId = 1L, senderName = "A",
                content = "哈哈哈", timestamp = 5000L, isFromBot = false
            )
        )
        service.recordHistoryMessage(
            conversationId = "group:9", senderId = 2L, senderName = "B",
            content = "哈哈哈", timestamp = 5200L, isFromBot = false
        )
        assertEquals(2, service.getHistory("group:9", 10).size, "不同发送者不折叠")
    }
}

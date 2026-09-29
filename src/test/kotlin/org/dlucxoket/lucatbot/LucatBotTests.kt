package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.model.message.CqParser
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.member.AtMessageMember
import org.dlucxoket.lucatbot.model.message.member.FaceMessageMember
import org.dlucxoket.lucatbot.model.message.member.ImageMessageMember
import org.dlucxoket.lucatbot.model.message.member.MemeMessageMember
import org.dlucxoket.lucatbot.model.message.member.ReplyMessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.dlucxoket.lucatbot.bot.memory.MemoryExtractor
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** ChatMessage.describe() 富内容描述：@/reply/图片/表情的占位、顺序与 base64 禁令 */
class ChatMessageTest {

    @Test
    fun `describe text only`() {
        val msg = ChatMessage(listOf(TextMessageMember("hello")))
        assertEquals("hello", msg.describe())
    }

    @Test
    fun `describe with image keeps order and marker`() {
        val msg = ChatMessage(listOf(TextMessageMember("看这个"), ImageMessageMember("http://img.example/a.jpg")))
        val described = msg.describe()
        assertTrue(described.contains("看这个"), described)
        assertTrue(described.contains("[图片"), described)
        assertTrue(described.contains("a.jpg"), described)
        assertFalse(described.contains("base64"), described)
    }

    @Test
    fun `describe at with nickname`() {
        val msg = ChatMessage(listOf(AtMessageMember(123456L, "小明"), TextMessageMember("这个你行吗")))
        assertEquals("@小明(123456) 这个你行吗", msg.describe())
    }

    @Test
    fun `describe at without nickname uses qq`() {
        val msg = ChatMessage(listOf(AtMessageMember(123456L), TextMessageMember("在吗")))
        assertEquals("@123456 在吗", msg.describe())
    }

    @Test
    fun `describe at uses resolver when member name missing`() {
        val msg = ChatMessage(listOf(AtMessageMember(777L), TextMessageMember("hi")))
        val described = msg.describe { qq -> if (qq == 777L) "小红" else null }
        assertEquals("@小红(777) hi", described)
    }

    @Test
    fun `describe at all`() {
        val msg = ChatMessage(listOf(AtMessageMember(-1L), TextMessageMember("集合")))
        assertEquals("@全体成员 集合", msg.describe())
    }

    @Test
    fun `describe reply with name and without`() {
        val named = ChatMessage(listOf(ReplyMessageMember(99L, "小明"), TextMessageMember("我也是")))
        assertEquals("回复 @小明：我也是", named.describe())
        val byId = ChatMessage(listOf(ReplyMessageMember(99L), TextMessageMember("我也是")))
        assertEquals("回复 #99：我也是", byId.describe())
    }

    @Test
    fun `describe face and meme`() {
        val face = ChatMessage(listOf(FaceMessageMember(14)))
        assertEquals("[表情:14]", face.describe())
        val meme = ChatMessage(listOf(MemeMessageMember("http://x/abc.jpg")))
        val memeText = meme.describe()
        assertTrue(memeText.startsWith("[表情包"), memeText)
        assertTrue(memeText.contains("abc.jpg"), memeText)
    }

    @Test
    fun `describe mixed keeps segment order`() {
        val msg = ChatMessage(
            listOf(
                ReplyMessageMember(5L, "小明"),
                AtMessageMember(123L, "小红"),
                TextMessageMember("看看这个"),
                ImageMessageMember("http://img.example/x.png"),
                FaceMessageMember(201),
                TextMessageMember("哈哈")
            )
        )
        val described = msg.describe()
        assertTrue(described.startsWith("回复 @小明：@小红(123) 看看这个"), described)
        assertTrue(described.contains("[图片"), described)
        assertTrue(described.contains("[表情:201]"), described)
        assertTrue(described.endsWith("哈哈"), described)
    }

    @Test
    fun `describe never dumps base64`() {
        val msg = ChatMessage(listOf(ImageMessageMember("base64://" + "A".repeat(200))))
        val described = msg.describe()
        assertEquals("[图片]", described)
        assertFalse(described.contains("base64://"))
    }

    @Test
    fun `hasNonTextContent`() {
        assertFalse(ChatMessage(listOf(TextMessageMember("hi"))).hasNonTextContent())
        assertTrue(ChatMessage(listOf(AtMessageMember(1L))).hasNonTextContent())
        assertTrue(ChatMessage(listOf(ReplyMessageMember(1L))).hasNonTextContent())
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

/** CQ 码字符串转 describe 同风格富文本的解析测试 */
class ShiroConverterCqTest {

    @Test
    fun `cq reply at text converts to describe style`() {
        val raw = "[CQ:reply,id=1683970346][CQ:at,qq=1923043992] 感觉玩街霸的太罕见了"
        val described = CqParser.describe(raw)
        assertEquals("回复 #1683970346：@1923043992 感觉玩街霸的太罕见了", described)
    }

    @Test
    fun `cq image keeps file name without base64`() {
        val raw = "看这个[CQ:image,file=BB83C7DE.jpg,url=https://multimedia.nt.qq.com.cn/download?appid=1407&fileid=xxx]"
        val described = CqParser.describe(raw)
        assertTrue(described.contains("看这个"), described)
        assertTrue(described.contains("[图片"), described)
        assertTrue(described.contains("BB83C7DE.jpg"), described)
        assertFalse(described.contains("fileid=xxx"), described)
    }

    @Test
    fun `cq face becomes face placeholder`() {
        val described = CqParser.describe("好耶[CQ:face,id=354,raw={...}]")
        assertEquals("好耶[表情:354]", described)
    }

    @Test
    fun `plain text without cq is kept as-is`() {
        assertEquals("你好啊", CqParser.describe("你好啊"))
    }

    @Test
    fun `cq at with card name`() {
        val members = CqParser.toMembers("[CQ:at,qq=123,card=小明] hi")
        val at = members.filterIsInstance<AtMessageMember>().single()
        assertEquals(123L, at.targetQqNumber)
        assertEquals("小明", at.targetName)
    }

    @Test
    fun `cq image sub_type 1 is meme`() {
        val members = CqParser.toMembers("[CQ:image,file=a.jpg,sub_type=1,url=http://x/a.jpg]")
        assertTrue(members.single() is MemeMessageMember)
    }
}

/** 心情状态机：boost / drop / decay 的类型与强度变化规则 */
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
    fun `toPrompt appends intensity with two decimals`() {
        assertEquals("困了（强度 0.90）", BotMood(BotMood.MoodType.SLEEPY, 0.9).toPrompt())
        assertEquals("现在心情不错（强度 0.50）", BotMood(BotMood.MoodType.HAPPY, 0.5).toPrompt())
        BotMood.MoodType.values().forEach { type ->
            val p = BotMood(type, 0.7).toPrompt()
            assertTrue(p.contains("强度 0.70"), "Mood $type prompt 应含强度数值：$p")
        }
    }
}

/** 会话上下文存取、容量上限与疲劳度提示 */
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

/** 正则提取名字/喜好；普通消息不写入记忆 */
class MemoryExtractorTest {

    @Test
    fun `extract name from message`() {
        val memories = MemoryExtractor.extract(MemorySubject.user(123L), "我叫张三")
        val nameMemory = memories.find { it.type == MemoryItem.MemoryType.FACT }
        assertNotNull(nameMemory)
        assertTrue(nameMemory!!.content.contains("张三"))
    }

    @Test
    fun `extract like from message`() {
        val memories = MemoryExtractor.extract(MemorySubject.user(123L), "我喜欢猫")
        val likeMemory = memories.find { it.type == MemoryItem.MemoryType.FACT }
        assertNotNull(likeMemory)
        assertTrue(likeMemory!!.content.contains("猫"))
    }

    @Test
    fun `does not store plain messages as memories`() {
        val memories = MemoryExtractor.extract(MemorySubject.user(123L), "你好")
        assertTrue(memories.none { it.type == MemoryItem.MemoryType.EVENT },
            "普通消息不应写入记忆")
        assertTrue(memories.isEmpty(), "无策略命中时不应产出记忆")
    }

    @Test
    fun `no fact when nothing to extract`() {
        val memories = MemoryExtractor.extract(MemorySubject.user(123L), "今天天气不错")
        assertTrue(memories.none { it.type == MemoryItem.MemoryType.FACT })
    }
}

/** 聊天记录落盘、类型识别与按物理消息去重 */
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

    @Test
    fun `rich live and cq crawl collapse`() {
        val file = File.createTempFile("hist-rich-dedup", ".json")
        val service = org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService(
            org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository(file.absolutePath)
        )
        service.recordHistoryMessage(
            conversationId = "group:1", senderId = 9L, senderName = "张三",
            content = "回复 #1683970346：@123 帮我找找",
            timestamp = 1000L, isFromBot = false
        )
        service.recordHistoryMessage(
            conversationId = "group:1", senderId = 9L, senderName = "张三",
            content = "[CQ:reply,id=1683970346][CQ:at,qq=123] 帮我找找",
            timestamp = 1500L, isFromBot = false
        )
        val history = service.getHistory("group:1", 10)
        assertEquals(1, history.size, "富内容与 CQ 原文应折叠")
        assertFalse(history.single().content.contains("[CQ:"), history.single().content)
    }
}

/** 读取时实时入档与爬楼入档的折叠去重 */
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

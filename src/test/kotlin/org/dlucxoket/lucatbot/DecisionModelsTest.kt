package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.data.ConversationAction
import org.dlucxoket.lucatbot.bot.agent.data.ConversationDecision
import org.dlucxoket.lucatbot.bot.agent.data.LlmJson
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickParser
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.MainLoopController
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionPickParserTest {

    @Test
    fun `open_conversation private`() {
        val pick = SessionPickParser.parse("""{"action":"open_conversation","params":{"type":"private","target":"123"},"reason":"想找我"}""")
        assertEquals(SessionPick(SessionPickType.PRIVATE, "123"), pick)
    }

    @Test
    fun `open_conversation group`() {
        val pick = SessionPickParser.parse("""{"action":"open_conversation","params":{"type":"group","target":"456"}}""")
        assertEquals(SessionPick(SessionPickType.GROUP, "456"), pick)
    }

    @Test
    fun `open_conversation proactive`() {
        val pick = SessionPickParser.parse("""{"action":"open_conversation","params":{"type":"proactive","target":"789"}}""")
        assertEquals(SessionPick(SessionPickType.PROACTIVE, "789"), pick)
    }

    @Test
    fun `idle returns null`() {
        assertNull(SessionPickParser.parse("""{"action":"idle","reason":"好累"}"""))
    }

    @Test
    fun `invalid json returns null`() {
        assertNull(SessionPickParser.parse("not json"))
        assertNull(SessionPickParser.parse(""))
    }

    @Test
    fun `missing target returns null`() {
        assertNull(SessionPickParser.parse("""{"action":"open_conversation","params":{"type":"private"}}"""))
    }

    @Test
    fun `fenced json works`() {
        val raw = """```json
{"action":"open_conversation","params":{"type":"private","target":"1"}}
```"""
        assertEquals(SessionPick(SessionPickType.PRIVATE, "1"), SessionPickParser.parse(raw))
    }
}

class ConversationDecisionParseTest {

    @Test
    fun `send with content`() {
        val d = ConversationDecision.parse("""{"action":"send","params":{"content":"哈哈好的"},"reason":"想回"}""")
        assertEquals(ConversationAction.SEND, d.action)
        assertEquals("哈哈好的", d.content)
    }

    @Test
    fun `read_more with count`() {
        val d = ConversationDecision.parse("""{"action":"read_more","params":{"count":7}}""")
        assertEquals(ConversationAction.READ_MORE, d.action)
        assertEquals(7, d.readCount)
    }

    @Test
    fun `read_more default count 10`() {
        val d = ConversationDecision.parse("""{"action":"read_more"}""")
        assertEquals(10, d.readCount)
    }

    @Test
    fun `return`() {
        val d = ConversationDecision.parse("""{"action":"return","reason":"累了"}""")
        assertEquals(ConversationAction.RETURN, d.action)
    }

    @Test
    fun `invalid falls back to RETURN`() {
        assertEquals(ConversationAction.RETURN, ConversationDecision.parse("garbage").action)
        assertEquals(ConversationAction.RETURN, ConversationDecision.parse("""{"action":"dance"}""").action)
    }

    @Test
    fun `send without content falls back to RETURN`() {
        val d = ConversationDecision.parse("""{"action":"send","params":{}}""")
        assertEquals(ConversationAction.RETURN, d.action)
    }

    @Test
    fun `reason not in params`() {
        val d = ConversationDecision.parse("""{"action":"send","params":{"content":"hi"},"reason":"内心戏"}""")
        assertEquals("hi", d.content)
        assertNull(d.params["reason"])
        assertEquals("内心戏", d.reason)
    }

    @Test
    fun `parses remember todo_add todo_done`() {
        val d = ConversationDecision.parse(
            """{"action":"send","params":{"content":"好的，我盯着"},"remember":"他叫我周三提醒他交房租","todo_add":"帮他看群","todo_done":"取快递"}"""
        )
        assertEquals(ConversationAction.SEND, d.action)
        assertEquals("他叫我周三提醒他交房租", d.remember)
        assertEquals("帮他看群", d.todo_add)
        assertEquals("取快递", d.todo_done)
    }

    @Test
    fun `side effect fields optional and blank dropped`() {
        val d = ConversationDecision.parse("""{"action":"return","reason":"累了"}""")
        assertNull(d.remember)
        assertNull(d.todo_add)
        assertNull(d.todo_done)

        val blank = ConversationDecision.parse(
            """{"action":"send","params":{"content":"hi"},"remember":"","todo_add":"  ","todo_done":null}"""
        )
        assertNull(blank.remember)
        assertNull(blank.todo_add)
        assertNull(blank.todo_done)
    }
}

class LlmJsonTest {

    @Test
    fun `extract strips fence and noise`() {
        val raw = "好的，我的选择是\n```json\n{\"action\":\"idle\"}\n```\n以上"
        assertTrue(LlmJson.extract(raw).startsWith("{"))
    }

    @Test
    fun `paramsOf empty when missing`() {
        val node = LlmJson.parseTree("""{"action":"idle"}""")!!
        assertTrue(LlmJson.paramsOf(node).isEmpty())
    }

    @Test
    fun `paramsOf returns map`() {
        val node = LlmJson.parseTree("""{"action":"send","params":{"content":"hi","count":3}}""")!!
        val p = LlmJson.paramsOf(node)
        assertEquals("hi", p["content"])
        assertEquals(3, p["count"])
    }
}

class ActionLogTest {

    @Test
    fun `append and dump`() {
        val log = ActionLog()
        log.append("查看消息列表（2条未读）")
        log.append("进入私聊 用户A(123)")
        val dump = log.dump()
        assertTrue(dump.contains("查看消息列表"))
        assertTrue(dump.contains("进入私聊"))
        assertTrue(dump.contains("["))  // 带时间
    }

    @Test
    fun `keeps only recent 20 lines`() {
        val log = ActionLog()
        repeat(30) { log.append("动作$it") }
        assertEquals(20, log.size())
        val dump = log.dump()
        assertFalse(dump.contains("动作0"))
        assertTrue(dump.contains("动作29"))
    }

    @Test
    fun `empty dump placeholder`() {
        assertEquals("（暂无）", ActionLog().dump())
    }
}

class IdleMillisTest {

    @Test
    fun `range is 3 to 20 min`() {
        val random = java.util.Random(42)
        BotMood.MoodType.values().forEach { type ->
            val mood = BotMood(type, 0.8)
            repeat(20) {
                val ms = MainLoopController.idleMillis(mood, random)
                assertTrue(ms in MainLoopController.MIN_IDLE_MS..MainLoopController.MAX_IDLE_MS,
                    "$type idle $ms out of range")
            }
        }
    }

    @Test
    fun `happy is shorter than sleepy`() {
        val random = java.util.Random(7)
        var happySum = 0L
        var sleepySum = 0L
        repeat(200) {
            happySum += MainLoopController.idleMillis(BotMood(BotMood.MoodType.HAPPY, 0.5), random)
            sleepySum += MainLoopController.idleMillis(BotMood(BotMood.MoodType.SLEEPY, 0.5), random)
        }
        assertTrue(happySum < sleepySum, "happy=$happySum sleepy=$sleepySum")
    }
}

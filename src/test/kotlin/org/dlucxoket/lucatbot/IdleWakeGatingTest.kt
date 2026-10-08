package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.MessageListController
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.model.event.message.FriendMessageEvent
import org.dlucxoket.lucatbot.model.event.message.GroupMessageEvent
import org.dlucxoket.lucatbot.model.group.Group
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.dlucxoket.lucatbot.model.user.Sex
import org.dlucxoket.lucatbot.model.user.User
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 空闲唤醒资格（应主人要求）：
 *
 * - **私聊 / @我 / @全体 / 回复我** = 冲我来的消息 → 才叫醒空闲的机器人
 * - **普通群聊消息** → 只攒未读，不唤醒（旧规则「未读 ≥3 条就唤醒」已删）
 */
class IdleWakeGatingTest {

    private fun groupEvent(text: String = "群友闲聊") = GroupMessageEvent(
        message = ChatMessage(listOf(TextMessageMember(text))),
        sender = User(222L, "群友", Sex.UNKNOWN, 0, "", card = null),
        group = Group(456L, "闲聊群"),
        timestamp = 1_000L,
        messageId = 11L
    )

    private fun privateEvent(text: String = "在吗") = FriendMessageEvent(
        message = ChatMessage(listOf(TextMessageMember(text))),
        sender = User(111L, "小明", Sex.UNKNOWN, 0, "", card = null),
        timestamp = 2_000L,
        messageId = 12L
    )

    @Test
    fun `plain group chatter never earns the wake flag`() {
        val bot = LucatBot()  // 默认 Idle

        // 旧规则：未读 ≥3 条就把空闲的机器人拽起来——已删
        repeat(3) {
            assertEquals(
                LucatBot.MessageAction.IGNORE,
                bot.handleMessage(groupEvent(), isAtMe = false, isAtAll = false),
                "普通群聊消息在空闲态只进未读"
            )
        }

        assertFalse(
            bot.hasWakeWorthyUnread("group:456"),
            "攒 3 条也不算唤醒资格（普通群聊不叫醒空闲的机器人）"
        )
    }

    @Test
    fun `private at-me and at-all unread are wake worthy`() {
        val bot = LucatBot()
        bot.handleMessage(privateEvent(), isAtMe = false, isAtAll = false)
        assertTrue(bot.hasWakeWorthyUnread("private:111"), "私聊要叫醒")

        bot.handleMessage(groupEvent(), isAtMe = true, isAtAll = false)
        assertTrue(bot.hasWakeWorthyUnread("group:456"), "@我要叫醒")

        val bot2 = LucatBot()
        bot2.handleMessage(groupEvent(), isAtMe = false, isAtAll = true)
        assertTrue(bot2.hasWakeWorthyUnread("group:456"), "@全体要叫醒")
    }

    @Test
    fun `reply to bot message is recognized via history and wake worthy`() {
        val chat = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist-reply", ".json").absolutePath)
        )
        chat.recordBotReply("group:456", "我发的消息", 55L)
        chat.recordHistoryMessage(
            "group:456", 222L, "群友", "别人的", timestamp = 1_000L,
            isFromBot = false, messageId = 66L
        )

        assertTrue(chat.isBotMessage("group:456", 55L), "自己发的消息 → 被回复的是我")
        assertFalse(chat.isBotMessage("group:456", 66L), "别人的不算")
        assertFalse(chat.isBotMessage("group:456", 999L), "档里查不到的老消息 → 宁可少唤醒")

        val bot = LucatBot()
        bot.handleMessage(
            groupEvent("回复 #55：你说得对"),
            isAtMe = false, isAtAll = false, isReplyToMe = true
        )
        assertTrue(bot.hasUnreadReplyToMe("group:456"), "回复我的未读带标记")
        assertTrue(bot.hasWakeWorthyUnread("group:456"), "回复我也要叫醒")
    }

    @Test
    fun `session list line carries reply marker`() {
        val bot = LucatBot()
        bot.handleMessage(
            groupEvent("回复 #55：你说得对"),
            isAtMe = false, isAtAll = false, isReplyToMe = true
        )

        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val controller = MessageListController(
            lucatBot = bot,
            sessionManager = SessionManager(),
            memoryService = MemoryService(
                JsonFileMemoryRepository(File.createTempFile("mem-reply", ".json").absolutePath)
            ),
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist-reply2", ".json").absolutePath)
            ),
            todoService = TodoService(
                JsonFileTodoRepository(File.createTempFile("todos-reply", ".json").absolutePath)
            ),
            actionLog = ActionLog(),
            sessionPickerAgent = SessionPickerAgent(llm),
            shortCircuitWhenIdle = false // 看 prompt：关掉 P1 空转短路
        )

        controller.pickSession(fromIdleWake = false)

        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("(@回复你)"), "会话行带「(@回复你)」标记：$prompt")
    }
}

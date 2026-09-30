package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.agent.NotificationAgent
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.agent.data.NotificationAction
import org.dlucxoket.lucatbot.bot.agent.data.NotificationDecision
import org.dlucxoket.lucatbot.bot.agent.data.NotificationRequest
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.ConversationController
import org.dlucxoket.lucatbot.bot.loop.MainLoopController
import org.dlucxoket.lucatbot.bot.loop.MessageListController
import org.dlucxoket.lucatbot.bot.loop.NotificationDispatcher
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.tool.data.HistoryMessageItem
import org.dlucxoket.lucatbot.model.event.message.FriendMessageEvent
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.dlucxoket.lucatbot.model.user.Sex
import org.dlucxoket.lucatbot.model.user.User
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 通知通道（NotificationDispatcher + NotificationAgent)——外来私聊/@ 消息的
 * 「手机横幅」三路决策：无视 / 建待办 / 当场跳转（含主循环忙时降级）。
 *
 * 另覆盖 LucatBot 侧：NOTIFY 类消息必须入未读（无视后选会话仍可见）。
 */
class NotificationDispatcherTest {

    /** 通知决策给定 + 会话决策 return 的完整通道夹具 */
    private class Fixture(notifyResponse: String) {
        val lucatBot = LucatBot()
        val sessionManager = org.dlucxoket.lucatbot.model.session.SessionManager()
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem-notify", ".json").absolutePath)
        )
        val chatHistoryService = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist-notify", ".json").absolutePath)
        )
        val todoService = TodoService(
            JsonFileTodoRepository(File.createTempFile("todos-notify", ".json").absolutePath)
        )
        val actionLog = ActionLog()
        val notifyLLM = FakeLLM(mutableListOf(notifyResponse))
        val convLLM = FakeLLM(mutableListOf("""{"action":"return"}"""))

        val conversationController = ConversationController(
            lucatBot = lucatBot,
            sessionManager = sessionManager,
            memoryService = memoryService,
            chatHistoryService = chatHistoryService,
            todoService = todoService,
            actionLog = actionLog,
            conversationAgent = ConversationAgent(convLLM),
            memoryAgent = MemoryAgent(FakeLLM(mutableListOf())),
            chatHistoryTool = FakeChatHistoryTool(
                listOf(HistoryMessageItem("用户A", "上文", 1_000L))
            ),
            sendMessageTool = FakeSendMessageTool(),
            imageSource = { null }
        )

        val mainLoopController = MainLoopController(
            lucatBot = lucatBot,
            messageListController = MessageListController(
                lucatBot = lucatBot,
                sessionManager = sessionManager,
                memoryService = memoryService,
                chatHistoryService = chatHistoryService,
                todoService = todoService,
                actionLog = actionLog,
                sessionPickerAgent = SessionPickerAgent(FakeLLM(mutableListOf()))
            ),
            conversationController = conversationController,
            actionLog = actionLog
        )

        val dispatcher = NotificationDispatcher(
            lucatBot = lucatBot,
            conversationController = conversationController,
            mainLoopController = mainLoopController,
            notificationAgent = NotificationAgent(notifyLLM),
            todoService = todoService,
            chatHistoryService = chatHistoryService,
            memoryService = memoryService,
            actionLog = actionLog
        )

        fun privateEvent(content: String = "在吗") = FriendMessageEvent(
            message = ChatMessage(listOf(TextMessageMember(content))),
            sender = User(2402567904L, "XENO", Sex.UNKNOWN, 0, "", card = null),
            timestamp = 1_000L,
            messageId = 43L
        )
    }

    // ------------------------------------------------------------------
    // Decision 解析
    // ------------------------------------------------------------------

    @Test
    fun `agent prompt teaches three way judgment with chat现场`() {
        val llm = FakeLLM(mutableListOf("""{"action":"ignore"}"""))
        NotificationAgent(llm).processObject(
            NotificationRequest(stateDescription = "正在聊天中（会话：private:1）", currentChat = "对方：在吗")
        )
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("\"ignore\" | \"todo\" | \"open\""), "三路动作：$prompt")
        assertTrue(prompt.contains("你正在聊的会话"), "聊天界面现场附带（判断打断价值）：$prompt")
        assertTrue(prompt.contains("别为琐事随便切走打断"), "聊天中倾向不切走的准则：$prompt")
        assertTrue(prompt.contains("已有相同待办就别再记一遍"), "todo 防重复准则：$prompt")
    }

    @Test
    fun `decision parses three actions and falls back to ignore`() {
        assertEquals(NotificationAction.IGNORE,
            NotificationDecision.parse("""{"action":"ignore","reason":"闲聊"}""").action)
        assertEquals(NotificationAction.TODO,
            NotificationDecision.parse("""{"action":"todo","params":{"content":"回复老哥"}}""").action)
        assertEquals(NotificationAction.OPEN,
            NotificationDecision.parse("""{"action":"open","reason":"点名问的"}""").action)

        // todo 缺 content / 垃圾输出 → 保守无视（消息仍在未读不丢）
        assertEquals(NotificationAction.IGNORE,
            NotificationDecision.parse("""{"action":"todo"}""").action)
        assertEquals(NotificationAction.IGNORE, NotificationDecision.parse("garbage").action)
        assertEquals(NotificationAction.IGNORE,
            NotificationDecision.parse("""{"action":"不明动作"}""").action)
        assertEquals("回复老哥",
            NotificationDecision.parse("""{"action":"todo","params":{"content":" 回复老哥 "}}""").todoContent)
    }

    // ------------------------------------------------------------------
    // 三路决策
    // ------------------------------------------------------------------

    @Test
    fun `ignore decision does nothing but logs`() {
        val f = Fixture("""{"action":"ignore","reason":"群闲聊"}""")

        f.dispatcher.handle(f.privateEvent("哈哈哈"), "哈哈哈", isAtMe = true, isAtAll = false)

        assertTrue(f.todoService.pending().isEmpty(), "无视不建待办")
        assertTrue(f.lucatBot.state !is LucatBot.State.InConversation, "无视不跳转")
        assertTrue(f.convLLM.prompts.isEmpty(), "无视不触发会话决策")
        assertTrue(f.actionLog.dump().contains("通知：无视"), "操作日志记录无视：${f.actionLog.dump()}")
    }

    @Test
    fun `todo decision creates todo item`() {
        val f = Fixture("""{"action":"todo","params":{"content":"查一下残阳群的事"}}""")

        f.dispatcher.handle(f.privateEvent("回头帮我查查"), "回头帮我查查", isAtMe = false, isAtAll = false)

        assertTrue(f.todoService.pendingSummary().contains("查一下残阳群的事"),
            "待办落库：${f.todoService.pendingSummary()}")
        assertTrue(f.lucatBot.state !is LucatBot.State.InConversation, "todo 不跳转")
        assertTrue(f.actionLog.dump().contains("通知转待办"), "操作日志记录")
    }

    @Test
    fun `open decision jumps into conversation when gate free`() {
        val f = Fixture("""{"action":"open","reason":"点名问的"}""")

        f.dispatcher.handle(f.privateEvent("在吗，急事"), "在吗，急事", isAtMe = true, isAtAll = false)

        assertEquals(1, f.convLLM.prompts.size, "当场跑了会话内决策（ConversationAgent）")
        assertTrue(f.lucatBot.state is LucatBot.State.ViewingConversations,
            "会话跑完回到消息列表")
        assertTrue(f.actionLog.dump().contains("通知点开"), "跳转成功记录（非排队）")
    }

    @Test
    fun `open decision degrades to queued when main loop busy`() {
        val f = Fixture("""{"action":"open"}""")

        // 另一线程占住会话执行锁（模拟主循环正跑会话）
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            f.mainLoopController.tryRunSessionExclusive {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true }
        holder.start()
        assertTrue(held.await(2, TimeUnit.SECONDS), "锁已被占")

        f.dispatcher.handle(f.privateEvent("急事"), "急事", isAtMe = true, isAtAll = false)

        assertTrue(f.convLLM.prompts.isEmpty(), "主循环忙不并发跑第二个会话循环")
        assertTrue(f.actionLog.dump().contains("排队"), "降级入未读排队：${f.actionLog.dump()}")
        release.countDown()
        holder.join(2_000)
    }

    // ------------------------------------------------------------------
    // LucatBot 侧：NOTIFY 消息入未读（无视后选会话可见）
    // ------------------------------------------------------------------

    @Test
    fun `notify messages land in unread queue`() {
        val bot = LucatBot()
        val event = FriendMessageEvent(
            message = ChatMessage(listOf(TextMessageMember("@我 在吗"))),
            sender = User(111L, "小明", Sex.UNKNOWN, 0, "", card = null),
            timestamp = 1_000L,
            messageId = 7L
        )

        // 看列表时收到 @我 → NOTIFY 且入未读
        bot.backToConversationList()
        assertEquals(LucatBot.MessageAction.NOTIFY, bot.handleMessage(event, isAtMe = true, isAtAll = false))
        assertTrue(bot.peekUnread("private:111").isNotEmpty(), "列表态 @我 入未读（无视后可见）")

        // 会话中别的会话 @我 → NOTIFY 且入未读
        bot.enterConversation("group:99", LucatBot.ConversationType.GROUP)
        assertEquals(LucatBot.MessageAction.NOTIFY, bot.handleMessage(event, isAtMe = true, isAtAll = false))
        assertTrue(bot.peekUnread("private:111").isNotEmpty(), "会话态外来 @我 入未读")
    }
}

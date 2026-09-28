package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationAction
import org.dlucxoket.lucatbot.bot.agent.data.ConversationDecision
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerRequest
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.ConversationController
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.tool.SendMessageRequest
import org.dlucxoket.lucatbot.bot.tool.SendMessageResult
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.bot.tool.data.HistoryMessageItem
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** 依次吐出预设 LLM 响应 */
class FakeLLM(private val responses: MutableList<String>) : LLM {
    val prompts = mutableListOf<String>()
    override fun ask(systemPrompt: String, question: String): String {
        prompts += systemPrompt
        return if (responses.isEmpty()) """{"action":"return"}""" else responses.removeAt(0)
    }
}

/** 假聊天记录工具 */
class FakeChatHistoryTool(
    private val lines: List<HistoryMessageItem>
) : Tool<ChatHistoryRequest, ChatHistoryResult>() {
    override val name = "FakeChatHistoryTool"
    override val description = ""
    override val parameterHint = ""
    override val inputClass = ChatHistoryRequest::class.java
    override fun handle(input: ChatHistoryRequest): ChatHistoryResult {
        return ChatHistoryResult(
            targetId = input.targetId,
            messages = lines.takeLast(input.count),
            success = true
        )
    }
}

/** 假发送工具：记录发送内容 */
class FakeSendMessageTool : Tool<SendMessageRequest, SendMessageResult>() {
    val sent = mutableListOf<String>()
    var fail = false
    override val name = "FakeSendMessageTool"
    override val description = ""
    override val parameterHint = ""
    override val inputClass = SendMessageRequest::class.java
    override fun handle(input: SendMessageRequest): SendMessageResult {
        if (fail) return SendMessageResult(success = false, error = "mock fail")
        sent += input.content
        return SendMessageResult(success = true)
    }
}

class ConversationControllerTest {

    private fun buildController(
        llm: FakeLLM,
        sendTool: FakeSendMessageTool = FakeSendMessageTool(),
        historyCount: Int = 5,
        replyWaitMs: Long = 0L,
        todoService: TodoService = TodoService(
            JsonFileTodoRepository(File.createTempFile("todos", ".json").apply { delete() }.absolutePath)
        )
    ): Triple<ConversationController, LucatBot, FakeSendMessageTool> {
        val lucatBot = LucatBot()
        val sessionManager = SessionManager()
        val memoryService = MemoryService(JsonFileMemoryRepository(File.createTempFile("mem", ".json").absolutePath))
        val chatHistoryService = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist", ".json").absolutePath)
        )
        val actionLog = ActionLog()
        val historyTool = FakeChatHistoryTool(
            (1..historyCount).map { HistoryMessageItem("用户A", "消息$it", it * 1000L) }
        )
        val controller = ConversationController(
            lucatBot = lucatBot,
            sessionManager = sessionManager,
            memoryService = memoryService,
            chatHistoryService = chatHistoryService,
            todoService = todoService,
            actionLog = actionLog,
            conversationAgent = ConversationAgent(llm),
            chatHistoryTool = historyTool,
            sendMessageTool = sendTool,
            initialHistoryCount = 3,
            replyWaitMs = replyWaitMs
        )
        return Triple(controller, lucatBot, sendTool)
    }

    @Test
    fun `return decision exits to list`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return","reason":"累了"}"""))
        val (controller, lucatBot, _) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
    }

    @Test
    fun `send then return`() {
        // replyWaitMs=0：send 后无人接话应直接离开，不会连发
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"在吗"}}""",
            """{"action":"send","params":{"content":"别生气啦"}}"""
        ))
        val (controller, lucatBot, sendTool) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        assertEquals(listOf("在吗"), sendTool.sent, "无人接话时不应连发")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
    }

    @Test
    fun `send waits for reply then continues`() {
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"在吗"}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, sendTool) = buildController(llm, replyWaitMs = 3_000L)

        val feeder = Thread {
            Thread.sleep(150L)
            lucatBot.enqueuePendingInsert("private:123", 123L, "用户A", "在的")
        }
        feeder.isDaemon = true
        feeder.start()

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        assertEquals(listOf("在吗"), sendTool.sent)
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
    }

    @Test
    fun `read_more has no forced exit and prompt shows crawl stats`() {
        // 10 次 read_more：旧 stale 上限（6 轮）会中途强制退出，现在应全部放行，
        // 由 LLM 结合 prompt 里的爬楼状态自行决定（FakeLLM 耗尽后兜底 return）
        val responses = MutableList(10) { """{"action":"read_more","params":{"count":5}}""" }
        val llm = FakeLLM(responses)
        val (controller, lucatBot, _) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        assertEquals(11, llm.prompts.size, "10 次 read_more 不应被代码强制打断，prompts=${llm.prompts.size}")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
        val lastPrompt = llm.prompts.last()
        assertTrue(lastPrompt.contains("爬楼状态"), "prompt 应包含爬楼状态段")
        assertTrue(lastPrompt.contains("已爬楼 10 次"), "应显示累计爬楼次数")
        assertTrue(lastPrompt.contains("已翻到最早消息"), "应提示已翻到历史开头")
    }

    @Test
    fun `proactive must send icebreak first`() {
        // 开局 return → 强制破冰 send
        val llm = FakeLLM(mutableListOf(
            """{"action":"return"}""",
            """{"action":"return"}"""
        ))
        val (controller, _, sendTool) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PROACTIVE, "999"))
        assertTrue(sendTool.sent.isNotEmpty(), "proactive 开局必须先 send")
    }

    @Test
    fun `invalid llm output falls back to return without send`() {
        val llm = FakeLLM(mutableListOf("totally not json"))
        val (controller, lucatBot, sendTool) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        assertTrue(sendTool.sent.isEmpty())
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
    }

    @Test
    fun `send failure exits loop`() {
        val llm = FakeLLM(mutableListOf("""{"action":"send","params":{"content":"hi"}}"""))
        val sendTool = FakeSendMessageTool().apply { fail = true }
        val (controller, lucatBot, _) = buildController(llm, sendTool = sendTool)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
    }

    @Test
    fun `group pick enters group conversation then returns`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val (controller, lucatBot, _) = buildController(llm)

        var sawInConversation = false
        // 用 actionLog 无法直接观察中间态，这里验证最终态即可
        controller.run(SessionPick(SessionPickType.GROUP, "42"))
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
        assertFalse(sawInConversation)
    }

    @Test
    fun `todo_add in decision lands in TodoService and prompt has todo section`() {
        val todoService = TodoService(
            JsonFileTodoRepository(File.createTempFile("todos", ".json").apply { delete() }.absolutePath)
        )
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"行，我帮你看着"},"todo_add":"帮他看群"}"""
        ))
        val (controller, lucatBot, sendTool) = buildController(llm, replyWaitMs = 0L, todoService = todoService)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(listOf("帮他看群"), todoService.pending().map { it.content }, "todo_add 应写入 TodoService")
        assertEquals(listOf("行，我帮你看着"), sendTool.sent)
        val prompt = llm.prompts.first()
        assertTrue(prompt.contains("### 待办"), "决策 prompt 应含 ### 待办 段")
        assertTrue(prompt.contains("（无待办）"), "决策时清单为空应显示占位")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
    }

    @Test
    fun `pending todos injected into decision prompt`() {
        val todoService = TodoService(
            JsonFileTodoRepository(File.createTempFile("todos", ".json").apply { delete() }.absolutePath)
        )
        todoService.add("提醒他交房租", "private:123")
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val (controller, _, _) = buildController(llm, todoService = todoService)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        val prompt = llm.prompts.first()
        assertTrue(prompt.contains("### 待办"))
        assertTrue(prompt.contains("提醒他交房租"), "pending 待办应注入 prompt")
    }
}

class SessionPickerAgentParseViaFakeLLMTest {

    @Test
    fun `agent converts envelope to SessionPick`() {
        val llm = FakeLLM(mutableListOf(
            """{"action":"open_conversation","params":{"type":"private","target":"1"},"reason":"想找我"}"""
        ))
        val agent = SessionPickerAgent(llm)
        val pick = agent.processObject(SessionPickerRequest())
        assertEquals(SessionPick(SessionPickType.PRIVATE, "1"), pick)
    }

    @Test
    fun `agent idle returns null`() {
        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val agent = SessionPickerAgent(llm)
        assertNull(agent.processObject(SessionPickerRequest()))
    }

    @Test
    fun `prompt contains action log and sessions`() {
        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val agent = SessionPickerAgent(llm)
        val log = ActionLog()
        log.append("查看消息列表（1条未读）")
        agent.processObject(
            SessionPickerRequest(
                actionLog = log.dump(),
                sessions = listOf(
                    org.dlucxoket.lucatbot.bot.agent.data.SessionSummaryLine(
                        conversationType = "私聊",
                        name = "用户A",
                        id = "1",
                        time = "14:02",
                        unreadCount = 2,
                        lastMessage = "你好"
                    )
                )
            )
        )
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("用户A"))
        assertTrue(prompt.contains("查看消息列表"))
        assertTrue(prompt.contains("open_conversation"))
    }
}

class ConversationAgentFallbackTest {

    @Test
    fun `processObject uses envelope and fallback`() {
        val llm = FakeLLM(mutableListOf("""{"action":"send","params":{"content":"哈"}}"""))
        val agent = ConversationAgent(llm)
        val d = agent.processObject(ConversationRequest())
        assertEquals("哈", d.content)

        val llm2 = FakeLLM(mutableListOf("???"))
        val agent2 = ConversationAgent(llm2)
        assertEquals(ConversationAction.RETURN, agent2.processObject(ConversationRequest()).action)
    }

    @Test
    fun `prompt contains action log`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val agent = ConversationAgent(llm)
        val log = ActionLog()
        log.append("爬楼 10 条")
        agent.processObject(ConversationRequest(actionLog = log.dump()))
        assertTrue(llm.prompts.single().contains("爬楼 10 条"))
    }
}

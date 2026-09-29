package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.MessageListController
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 消息列表的「我上次发言 X 前」+ 选会话优先级引导 + 全量记忆概览注入
 */
class SessionListSpokeAgoTest {

    @Test
    fun `lastBotSpeakTime counts bot replies only`() {
        val s = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist-spoke", ".json").absolutePath)
        )
        assertNull(s.lastBotSpeakTime("private:1"), "从没发过 → null")

        // 只有对方说过 → 仍为 null
        s.recordHistoryMessage(
            conversationId = "private:1", senderId = 1L, senderName = "甲",
            content = "在吗", timestamp = 1_000L, isFromBot = false
        )
        assertNull(s.lastBotSpeakTime("private:1"))

        // 机器人发言 → 非空且接近当前
        val before = System.currentTimeMillis()
        s.recordBotReply("private:1", "回你")
        val t = s.lastBotSpeakTime("private:1")
        assertNotNull(t, "机器人发过言 → 非空")
        assertTrue(t!! in (before - 5_000)..(System.currentTimeMillis() + 5_000))
    }

    @Test
    fun `list prompt shows my last spoke time never spoke and priority guidance`() {
        val chat = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist-spoke2", ".json").absolutePath)
        )
        val sessions = SessionManager()
        // 会话 A：我刚发言
        chat.recordBotReply("private:777", "刚发的消息")
        sessions.addMessage("private:777", "牢洛", "刚发的消息", isBot = true)
        // 会话 B：我从未发言
        sessions.addMessage("private:888", "小明", "在吗")

        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val controller = MessageListController(
            lucatBot = org.dlucxoket.lucatbot.bot.LucatBot(),
            sessionManager = sessions,
            memoryService = MemoryService(
                JsonFileMemoryRepository(File.createTempFile("mem-spoke", ".json").absolutePath)
            ),
            chatHistoryService = chat,
            todoService = TodoService(
                JsonFileTodoRepository(File.createTempFile("todos-spoke", ".json").absolutePath)
            ),
            actionLog = ActionLog(),
            sessionPickerAgent = SessionPickerAgent(llm)
        )

        controller.pickSession(fromIdleWake = false)

        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("我上次发言 刚刚"), "刚发过言的会话带时长：$prompt")
        assertTrue(prompt.contains("我还没说过话"), "没发过言的会话如实标注：$prompt")
        assertTrue(prompt.contains("选会话优先级"), "优先级规则：$prompt")
        assertTrue(prompt.contains("且还有未读"), "点明『刚发过言+有未读』优先：$prompt")
        assertTrue(prompt.contains("MemoryQueryTool"), "记忆关键词搜索引导：$prompt")
    }
}

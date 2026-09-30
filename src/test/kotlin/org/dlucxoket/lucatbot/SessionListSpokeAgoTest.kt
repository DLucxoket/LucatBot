package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.MessageListController
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
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
        assertTrue(prompt.contains("待办/记忆驱动"), "待办或记忆里的任务指向会话优先打开（实盘：受托去群里看却反复空转）：$prompt")
        assertTrue(prompt.contains("半途任务别丢"), "查到一半的任务优先接力办完（实盘：拿到 QQ 号却缩回热会话）：$prompt")    }

    @Test
    fun `suspected banned group shows mark with update date in session list`() {
        // 会话列表标记疑似禁言 + 更新日期（发送失败 retCode 打点、成功清除）
        val sessions = SessionManager()
        sessions.addMessage("group:555", "群友", "说话")
        val bot = org.dlucxoket.lucatbot.bot.LucatBot()
        bot.markSuspectedBan("group:555")

        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val controller = MessageListController(
            lucatBot = bot,
            sessionManager = sessions,
            memoryService = MemoryService(
                JsonFileMemoryRepository(File.createTempFile("mem-ban", ".json").absolutePath)
            ),
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist-ban", ".json").absolutePath)
            ),
            todoService = TodoService(
                JsonFileTodoRepository(File.createTempFile("todos-ban", ".json").absolutePath)
            ),
            actionLog = ActionLog(),
            sessionPickerAgent = SessionPickerAgent(llm)
        )

        controller.pickSession(fromIdleWake = false)

        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("【疑似禁言 $today】"),
            "会话行带疑似禁言标记+今日日期（更新日期）：$prompt")
        assertTrue(prompt.contains("进去也发不出消息"), "约束说明标记语义：$prompt")
    }

    @Test
    fun `proactive opens unknown targets for tasked contact`() {
        // 实盘：让机器人给从未聊过的延延发消息——旧约束「proactive 仅限可主动找的人」
        // 把任务对象拦死（不在列表、没聊过 → 两个候选区都进不去），放开为两类场景
        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val controller = MessageListController(
            lucatBot = org.dlucxoket.lucatbot.bot.LucatBot(),
            sessionManager = SessionManager(),
            memoryService = MemoryService(
                JsonFileMemoryRepository(File.createTempFile("mem-pro", ".json").absolutePath)
            ),
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist-pro", ".json").absolutePath)
            ),
            todoService = TodoService(
                JsonFileTodoRepository(File.createTempFile("todos-pro", ".json").absolutePath)
            ),
            actionLog = ActionLog(),
            sessionPickerAgent = SessionPickerAgent(llm)
        )

        controller.pickSession(fromIdleWake = false)

        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("任务需要联系的人/群"),
            "proactive 第二类场景（任务联系）：$prompt")
        assertTrue(prompt.contains("从未聊过、不在下方列表"),
            "点明可开列表外的目标会话：$prompt")
        assertTrue(prompt.contains("不在列表（如从未聊过的人）→ 用 proactive"),
            "待办驱动指引开新会话：$prompt")
    }

    @Test
    fun `session line carries memory brief of its own subject`() {
        // 泛概览对得上「有什么记忆」但对不上「哪条属于哪个会话」——
        // 行挂该主体记忆摘要，待办/记忆驱动才能与具体会话直接关联
        val sessions = SessionManager()
        sessions.addMessage("private:777", "延延", "在吗")
        val memSvc = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem-brief", ".json").absolutePath)
        )
        memSvc.rememberFact(MemorySubject.user(777L), "延延是我的QQ好友，QQ号3981541235")
        memSvc.rememberFact(MemorySubject.user(777L), "洛洛让我给他发消息，尚未发")

        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val controller = MessageListController(
            lucatBot = org.dlucxoket.lucatbot.bot.LucatBot(),
            sessionManager = sessions,
            memoryService = memSvc,
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist-brief", ".json").absolutePath)
            ),
            todoService = TodoService(
                JsonFileTodoRepository(File.createTempFile("todos-brief", ".json").absolutePath)
            ),
            actionLog = ActionLog(),
            sessionPickerAgent = SessionPickerAgent(llm)
        )

        controller.pickSession(fromIdleWake = false)

        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("（记忆：") , "会话行尾挂记忆摘要：$prompt")
        assertTrue(prompt.contains("延延是我的QQ好友"), "该主体的记忆内容：$prompt")
        assertTrue(prompt.contains("尚未发"), "未竟任务类记忆可见：$prompt")
        assertTrue(prompt.contains("行尾（记忆：…）"), "约束解释该标记语义：$prompt")
    }

    @Test
    fun `memory brief injects newest memories first`() {
        // 摘要取序：写入时间倒序 → 最新记忆排最前（最新事实优先被选会话看到）
        val sessions = SessionManager()
        sessions.addMessage("private:888", "老友", "hi")
        val repo = JsonFileMemoryRepository(File.createTempFile("mem-newest", ".json").absolutePath)
        val memSvc = MemoryService(repo)
        val subject = MemorySubject.user(888L)
        listOf(
            3_000L to "最新写的记忆",
            1_000L to "最旧写的记忆",
            2_000L to "次新写的记忆"
        ).forEach { (createdAt, content) ->
            repo.save(MemoryItem(
                userId = subject.id, subjectType = subject.type,
                type = MemoryItem.MemoryType.FACT, content = content, createdAt = createdAt
            ))
        }

        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val controller = MessageListController(
            lucatBot = org.dlucxoket.lucatbot.bot.LucatBot(),
            sessionManager = sessions,
            memoryService = memSvc,
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist-newest", ".json").absolutePath)
            ),
            todoService = TodoService(
                JsonFileTodoRepository(File.createTempFile("todos-newest", ".json").absolutePath)
            ),
            actionLog = ActionLog(),
            sessionPickerAgent = SessionPickerAgent(llm)
        )

        controller.pickSession(fromIdleWake = false)

        val prompt = llm.prompts[0]
        // system 约束里也有「行尾（记忆：…）」字样——先定位 user 段再取行内摘要
        val brief = prompt
            .substringAfter("\n=====USER=====\n")
            .substringAfter("（记忆：")
            .substringBefore("）")
        val newestIdx = brief.indexOf("最新写的记忆")
        val secondIdx = brief.indexOf("次新写的记忆")
        val oldestIdx = brief.indexOf("最旧写的记忆")
        assertTrue(newestIdx in 0 until secondIdx && secondIdx in 0 until oldestIdx,
            "最新优先（最新 → 次新 → 最旧）：$brief")
    }
}

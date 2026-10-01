package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.MessageListController
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.dlucxoket.lucatbot.bot.tool.ToolCatalog
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MessageListController.pickSession 的多步决策循环
 *
 * 覆盖 OOP 审查的最大测试缺口：工具动作执行后带结果重新决策、
 * 未知工具不打断循环、**连续报错兜底去空闲（成功工具轮不限）**。
 */

/** 必失败工具（构造 success=false + error 结果，触发连续报错护栏） */
class FakeFailTool : Tool<FakeFailTool.Request, FakeFailTool.Result>() {
    data class Request(val q: String = "")
    data class Result(val success: Boolean = true, val error: String? = null)
    override val name = "FakeFailTool"
    override val description = "功能：必然失败的工具。\n何时使用：测试专用。"
    override val parameterHint = "JSON: {\"q\": \"任意\"}"
    override val inputClass: Class<Request> = Request::class.java
    override fun handle(input: Request): Result = Result(success = false, error = "测试失败")
}
class MessageListControllerTest {

    private fun buildController(
        llm: FakeLLM,
        toolCatalog: ToolCatalog = ToolCatalog(toolsProvider = { listOf(FakeEchoTool()) }),
        /** 传入即可在外部断言 todo_add / todo_done 副作用 */
        todoService: TodoService = TodoService(
            JsonFileTodoRepository(File.createTempFile("todos", ".json").absolutePath)
        ),
        /** 传入即可在外部操作未读/冷却（会话冷却测试） */
        lucatBot: LucatBot = LucatBot(),
        /** 传入即可让某些会话进「最近活跃」列表 */
        sessionManager: SessionManager = SessionManager()
    ): Pair<MessageListController, ActionLog> {
        val controller = MessageListController(
            lucatBot = lucatBot,
            sessionManager = sessionManager,
            memoryService = MemoryService(
                JsonFileMemoryRepository(File.createTempFile("mem", ".json").absolutePath)
            ),
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist", ".json").absolutePath)
            ),
            todoService = todoService,
            actionLog = ActionLog(),
            sessionPickerAgent = SessionPickerAgent(llm),
            toolCatalog = toolCatalog
        )
        return controller to ActionLog()  // actionLog 内部实例外部不可见，断言走 prompt/返回值
    }

    @Test
    fun `multi step list_tools then tool then open returns pick with notes fed back`() {
        val llm = FakeLLM(mutableListOf(
            """{"action":"list_tools","params":{"query":"Echo"},"reason":"先看工具"}""",
            """{"action":"tool","params":{"name":"FakeEchoTool","input":{"q":"hi"}},"reason":"试调用"}""",
            """{"action":"open_conversation","params":{"type":"private","target":"42"},"reason":"去看"}"""
        ))
        val (controller, _) = buildController(llm)

        val pick = controller.pickSession(fromIdleWake = false)

        assertEquals(SessionPick(SessionPickType.PRIVATE, "42"), pick)
        val prompts = llm.prompts
        assertEquals(3, prompts.size, "list_tools / tool / open 各一轮")
        // 第 2 轮：user 侧应有目录（三要素）
        assertTrue(prompts[1].contains("### FakeEchoTool"), "第 2 轮应看到目录：${prompts[1]}")
        assertTrue(prompts[1].contains("功能：把参数 q 原样返回"), prompts[1])
        // 第 3 轮：应有执行结果 + 工具状态行
        assertTrue(prompts[2].contains("执行结果"), "第 3 轮应看到结果：${prompts[2]}")
        assertTrue(prompts[2].contains("\"echoed\":\"hi\""), prompts[2])
        assertTrue(prompts[2].contains("工具状态"), "状态计数行应注入：${prompts[2]}")
    }

    @Test
    fun `unknown tool feeds error note and loop continues`() {
        val llm = FakeLLM(mutableListOf(
            """{"action":"tool","params":{"name":"NoSuchTool","input":{}},"reason":"瞎调"}""",
            """{"action":"idle","reason":"算了"}"""
        ))
        val (controller, _) = buildController(llm)

        assertNull(controller.pickSession(fromIdleWake = false), "idle → null")
        assertEquals(2, llm.prompts.size, "工具失败不打断循环")
        assertTrue(llm.prompts[1].contains("不存在"), "第 2 轮应看到错误说明：${llm.prompts[1]}")
    }

    @Test
    fun `consecutive tool errors hit limit and fall back to idle`() {
        // 护栏语义（2026-10-01 调整）：只限**连续调用错误**（上限 3）——
        // 3 次失败执行后，第 4 个工具决策兜底去空闲、不再执行
        val script = MutableList(4) { """{"action":"tool","params":{"name":"FakeFailTool","input":{}}}""" }
        val llm = FakeLLM(script)
        val (controller, _) = buildController(
            llm, toolCatalog = ToolCatalog(toolsProvider = { listOf(FakeFailTool()) })
        )

        assertNull(controller.pickSession(fromIdleWake = false), "连续报错超限兜底 idle")
        assertEquals(4, llm.prompts.size, "恰好 4 轮（3 次失败执行 + 1 轮兜底）")
        // 状态行应显示连续报错 3（第 4 轮未执行记录）
        assertTrue(llm.prompts[3].contains("连续报错 3"), llm.prompts[3])
        assertTrue(llm.prompts[3].contains("将强制停止"), "报错点名提示注入：${llm.prompts[3]}")
    }

    @Test
    fun `successful tool rounds are never limited`() {
        // 旧版 6 步硬上限在正常排查中途逼停（实盘第 7 轮被逼停）——
        // 现多轮**成功**的 list_tools 不触发任何限制，直到模型自己 idle
        val script = MutableList(8) { """{"action":"list_tools"}""" }
        script += """{"action":"idle","reason":"看完了"}"""
        val llm = FakeLLM(script)
        val (controller, _) = buildController(llm)

        assertNull(controller.pickSession(fromIdleWake = false), "模型自己 idle 才结束")
        assertEquals(9, llm.prompts.size, "8 轮 list_tools 全部执行、无中途兜底")
        // prompts[7] = 第 8 轮决策前注入 → 此时已记录 7 次（第 8 次尚未执行）
        assertTrue(llm.prompts[7].contains("连续工具步 7"), "计数照常展示（仅可观测）：${llm.prompts[7]}")
        assertTrue(llm.prompts[7].contains("连续报错 0"), "无报错：${llm.prompts[7]}")
        assertTrue(llm.prompts[7].contains("连续报错 7 次将强制停止").not(), "报错 0 不提限制：${llm.prompts[7]}")
    }

    @Test
    fun `simple idle needs no tool round`() {
        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val (controller, _) = buildController(llm)
        assertNull(controller.pickSession(fromIdleWake = false))
        assertEquals(1, llm.prompts.size)
    }

    @Test
    fun `idle decision exposes llm-chosen sleep seconds`() {
        // 空闲时长由 LLM 决定：IDLE 带 params.seconds → 透传给主循环，取走即清零
        val llm = FakeLLM(mutableListOf("""{"action":"idle","params":{"seconds":420},"reason":"没啥事"}"""))
        val (controller, _) = buildController(llm)

        assertNull(controller.pickSession(fromIdleWake = false))
        assertEquals(420, controller.consumeIdleSeconds(), "LLM 决定的空闲秒数透传")
        assertEquals(0, controller.consumeIdleSeconds(), "取走即清零（不复用上一轮）")
    }

    @Test
    fun `open pick keeps idle seconds at zero`() {
        val llm = FakeLLM(mutableListOf("""{"action":"open_conversation","params":{"type":"private","target":"42"}}"""))
        val (controller, _) = buildController(llm)

        assertNotNull(controller.pickSession(fromIdleWake = false))
        assertEquals(0, controller.consumeIdleSeconds(), "选中会话不带空闲时长（上轮残留已清零）")
    }

    @Test
    fun `todo side effects are applied before action and feed next prompt`() {
        val todoService = TodoService(
            JsonFileTodoRepository(File.createTempFile("todos-todo", ".json").absolutePath)
        )
        val llm = FakeLLM(mutableListOf(
            // 第 1 轮：记待办 + 顺手标一条完成（完成匹配不上 → 不打断循环），随后去看会话
            """{"action":"open_conversation","params":{"type":"private","target":"42"},"todo_add":"帮他取快递","todo_done":"不存在的待办","reason":"顺手记一下"}""",
        ))
        val (controller, _) = buildController(llm, todoService = todoService)

        val pick = controller.pickSession(fromIdleWake = false)

        assertEquals(SessionPick(SessionPickType.PRIVATE, "42"), pick, "副作用不打断 action 执行")
        assertEquals(listOf("帮他取快递"), todoService.pending().map { it.content }, "todo_add 已落库")
        // 同轮 prompt 里的待办段是决策前的快照；下一轮（若有）才会看到新条目——
        // 这里断言规则文案与常驻的待办段存在
        assertTrue(llm.prompts[0].contains("### 待办"), "待办段固定注入：${llm.prompts[0]}")
        assertTrue(llm.prompts[0].contains("\"todo_add\""), "prompt 应含 todo_add 输出规则")
    }

    @Test
    fun `todo_done marks pending todo complete across rounds`() {
        val todoService = TodoService(
            JsonFileTodoRepository(File.createTempFile("todos-done", ".json").absolutePath)
        )
        todoService.add("帮群友修电脑")
        val llm = FakeLLM(mutableListOf(
            """{"action":"idle","todo_done":"修电脑","reason":"搞定了"}"""
        ))
        val (controller, _) = buildController(llm, todoService = todoService)

        assertNull(controller.pickSession(fromIdleWake = false))
        assertTrue(todoService.pending().isEmpty(), "todo_done 应按内容匹配并标完成")
        // 下一轮（本次为最后一轮）无待办时摘要保底文案
        assertTrue(llm.prompts[0].contains("帮群友修电脑"), "完成前的清单应注入 prompt：${llm.prompts[0]}")
    }

    @Test
    fun `todos section is always present even when empty`() {
        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val (controller, _) = buildController(llm)
        controller.pickSession(fromIdleWake = false)
        assertTrue(llm.prompts[0].contains("### 待办"), "待办段固定出现")
        assertTrue(llm.prompts[0].contains("（无待办）"), "空清单保底文案")
    }

    @Test
    fun `conversation in cooldown is flagged in prompt instead of filtered`() {
        // 实盘：群里只回了个表情包 → return → 下一轮 pick 又带未读进同一群 → 立刻又 return。
        // 改用 prompt 约束：列表照常可见，但「刚退出（冷却中）」段点名 + 系统规则别马上再进
        val sessionManager = SessionManager()
        sessionManager.addMessage("group:811788212", "群友", "还在聊", isBot = false)
        val lucatBot = LucatBot()
        lucatBot.markConversationLeft("group:811788212")

        val cooling = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val (c1, _) = buildController(cooling, lucatBot = lucatBot, sessionManager = sessionManager)
        c1.pickSession(fromIdleWake = false)
        val p1 = cooling.prompts[0]
        assertTrue(p1.contains("811788212"), "列表照常可见（不硬过滤）：$p1")
        assertTrue(p1.contains("### 刚退出（冷却中"), "冷却提示段注入：$p1")
        assertTrue(p1.contains("刚退出过"), "点名冷却中的会话：$p1")
        assertTrue(p1.contains("刚退出的会话别马上再进"), "系统规则约束别马上再进：$p1")

        // 冷却期满 → 冷却段消失（列表仍在）
        lucatBot.markConversationLeft(
            "group:811788212",
            at = System.currentTimeMillis() - LucatBot.CONVERSATION_COOLDOWN_MS - 1_000
        )
        val after = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val (c2, _) = buildController(after, lucatBot = lucatBot, sessionManager = sessionManager)
        c2.pickSession(fromIdleWake = false)
        assertFalse(after.prompts[0].contains("刚退出过"),
            "冷却结束不再提示：${after.prompts[0]}")
    }
}

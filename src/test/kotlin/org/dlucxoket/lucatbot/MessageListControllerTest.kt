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
import org.dlucxoket.lucatbot.bot.tool.ToolCatalog
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MessageListController.pickSession 的多步决策循环
 *
 * 覆盖 OOP 审查的最大测试缺口：工具动作执行后带结果重新决策、
 * 未知工具不打断循环、连续工具步硬上限兜底去空闲。
 */
class MessageListControllerTest {

    private fun buildController(
        llm: FakeLLM,
        toolCatalog: ToolCatalog = ToolCatalog(toolsProvider = { listOf(FakeEchoTool()) }),
        /** 传入即可在外部断言 todo_add / todo_done 副作用 */
        todoService: TodoService = TodoService(
            JsonFileTodoRepository(File.createTempFile("todos", ".json").absolutePath)
        )
    ): Pair<MessageListController, ActionLog> {
        val controller = MessageListController(
            lucatBot = LucatBot(),
            sessionManager = SessionManager(),
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
    fun `consecutive tool steps hit hard limit and fall back to idle`() {
        // 硬上限 6：第 7 个工具决策到来时兜底去空闲，不再执行
        val script = MutableList(7) { """{"action":"list_tools"}""" }
        val llm = FakeLLM(script)
        val (controller, _) = buildController(llm)

        assertNull(controller.pickSession(fromIdleWake = false), "超限兜底 idle")
        assertEquals(7, llm.prompts.size, "恰好 7 轮（6 步执行 + 1 轮兜底）")
        // 状态行应显示连续 6 步（第 7 轮未执行记录）
        assertTrue(llm.prompts[6].contains("连续工具步 6"), llm.prompts[6])
    }

    @Test
    fun `simple idle needs no tool round`() {
        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val (controller, _) = buildController(llm)
        assertNull(controller.pickSession(fromIdleWake = false))
        assertEquals(1, llm.prompts.size)
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
}

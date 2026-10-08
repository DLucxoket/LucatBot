package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.adapter.web.StateController
import org.dlucxoket.lucatbot.adapter.web.TokenUsageController
import org.dlucxoket.lucatbot.adapter.web.TodoController
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.llm.JsonlTokenUsageStore
import org.dlucxoket.lucatbot.bot.llm.TokenUsageAggregator
import org.dlucxoket.lucatbot.bot.llm.TokenUsageChannel
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.jupiter.api.Assertions.*

/**
 * Web API（`web/` Vue 面板的数据源）
 *
 * - token 统计：summary 四维分组 / timeseries 分桶 / calls 明细
 * - 状态总览：心情、待办、记忆、状态机当前态
 *
 * 用 standalone MockMvc（不起 Spring 上下文）；聚合器**构造注入**临时文件存储
 * ——不碰全局状态，测试之间天然隔离（DIP 修复后的写法）。
 */
class WebApiTest {

    private lateinit var file: File
    private lateinit var ledger: TokenUsageAggregator

    @BeforeEach
    fun setUp() {
        file = File.createTempFile("token-api", ".jsonl").apply { delete() }
        ledger = TokenUsageAggregator(JsonlTokenUsageStore(file.absolutePath), summaryEvery = 0)
        ledger.record("ConversationAgent", "private:123", TokenUsageChannel.TEXT, "m1", 100, 20)
        ledger.record("ConversationAgent", "private:123", TokenUsageChannel.IMAGE, "m1", 900, 30)
        ledger.record("SessionPickerAgent", "消息列表", TokenUsageChannel.TEXT, "m2", 50, 10)
    }

    @AfterEach
    fun tearDown() {
        file.delete()
    }

    private fun mvc(controller: Any) = MockMvcBuilders.standaloneSetup(controller).build()

    @Test
    fun `summary returns four dimensions grouped and sorted`() {
        mvc(TokenUsageController(ledger))
            .perform(get("/api/token/summary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total.calls").value(3))
            .andExpect(jsonPath("$.total.input").value(1050))
            .andExpect(jsonPath("$.total.output").value(60))
            .andExpect(jsonPath("$.noUsageCalls").value(0))
            // 维度一：通道（固定顺序 文字 → 图片，零值也展示）
            .andExpect(jsonPath("$.byChannel[0].name").value("文字"))
            .andExpect(jsonPath("$.byChannel[0].calls").value(2))
            .andExpect(jsonPath("$.byChannel[1].name").value("图片"))
            .andExpect(jsonPath("$.byChannel[1].input").value(900))
            // 维度二：agent（合计降序）
            .andExpect(jsonPath("$.byAgent[0].name").value("ConversationAgent"))
            .andExpect(jsonPath("$.byAgent[0].calls").value(2))
            .andExpect(jsonPath("$.byAgent[1].name").value("SessionPickerAgent"))
            // 维度三：会话
            .andExpect(jsonPath("$.byConversation[0].name").value("private:123"))
            .andExpect(jsonPath("$.byConversation[1].name").value("消息列表"))
            // 维度四：模型
            .andExpect(jsonPath("$.byModel[0].name").value("m1"))
            .andExpect(jsonPath("$.byModel[1].name").value("m2"))
    }

    @Test
    fun `timeseries buckets records by day and by hour`() {
        // 用固定 epoch 覆盖明细：全部落在「今天」，日桶 = 1 个、时桶 = 3 个（确定性）
        val today = LocalDate.now(org.dlucxoket.lucatbot.bot.persona.TimeFormat.ZONE)
            .atStartOfDay(org.dlucxoket.lucatbot.bot.persona.TimeFormat.ZONE).toInstant().toEpochMilli()
        file.writeText(
            (1..3).joinToString("\n") { i ->
                """{"epoch":${today + i * 3_600_000L},"ts":"2026-01-01 0$i:00:00","agent":"ConversationAgent","conversation":"private:1","channel":"文字","model":"m1","calls":1,"in":10,"out":5,"reported":true}"""
            } + "\n",
            Charsets.UTF_8
        )
        // 换一个只读该文件的聚合器（明细端点读存储，不看内存聚合）
        val fresh = TokenUsageAggregator(JsonlTokenUsageStore(file.absolutePath), summaryEvery = 0)

        mvc(TokenUsageController(fresh))
            .perform(get("/api/token/timeseries").param("bucket", "day"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].bucket").value(LocalDate.now(org.dlucxoket.lucatbot.bot.persona.TimeFormat.ZONE).format(DateTimeFormatter.ISO_LOCAL_DATE)))
            .andExpect(jsonPath("$[0].calls").value(3))
            .andExpect(jsonPath("$[0].total").value(45))

        mvc(TokenUsageController(fresh))
            .perform(get("/api/token/timeseries").param("bucket", "hour").param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    @Test
    fun `calls returns raw records newest first`() {
        // 固定三条不同时间的明细 → 排序确定（同一毫秒写入时顺序依赖文件序，不断言它）
        val base = System.currentTimeMillis() - 60_000
        file.writeText(
            listOf(
                """{"epoch":${base},"ts":"2026-01-01 10:00:00","agent":"ConversationAgent","conversation":"private:123","channel":"文字","model":"m1","calls":1,"in":100,"out":20,"reported":true}""",
                """{"epoch":${base + 1_000},"ts":"2026-01-01 10:00:01","agent":"ConversationAgent","conversation":"private:123","channel":"图片","model":"m1","calls":1,"in":900,"out":30,"reported":true}""",
                """{"epoch":${base + 2_000},"ts":"2026-01-01 10:00:02","agent":"SessionPickerAgent","conversation":"消息列表","channel":"文字","model":"m2","calls":1,"in":50,"out":10,"reported":true}"""
            ).joinToString("\n") + "\n",
            Charsets.UTF_8
        )
        val fresh = TokenUsageAggregator(JsonlTokenUsageStore(file.absolutePath), summaryEvery = 0)

        val body = mvc(TokenUsageController(fresh))
            .perform(get("/api/token/calls").param("limit", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].conversation").value("消息列表"))
            .andExpect(jsonPath("$[1].channel").value("图片"))
            .andExpect(jsonPath("$[2].conversation").value("private:123"))
            .andExpect(jsonPath("$[0].reported").value(true))
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        assertTrue(body.contains("\"model\":\"m1\""), "明细带模型维度：$body")
        assertTrue(body.contains("\"input\":900"), "明细带入参 token：$body")
    }

    @Test
    fun `state overview exposes mood todos and memories`() {
        val memoryService = MemoryService(JsonFileMemoryRepository(File.createTempFile("mem", ".json").absolutePath))
        memoryService.rememberFact(MemorySubject.user(1L), "他喜欢蓝色")
        val todoService = TodoService(JsonFileTodoRepository(File.createTempFile("todos", ".json").absolutePath))
        todoService.add("去XX群看看谁在找我")

        val controller = StateController(
            lucatBot = LucatBot(),
            sessionManager = SessionManager(),
            memoryService = memoryService,
            todoService = todoService,
            actionLog = ActionLog()
        )

        mvc(controller)
            .perform(get("/api/state/overview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mood.type").exists())
            .andExpect(jsonPath("$.mood.intensity").isNumber)
            .andExpect(jsonPath("$.mood.text").isString)
            .andExpect(jsonPath("$.state").value("Idle"))
            .andExpect(jsonPath("$.pendingTodos.length()").value(1))
            .andExpect(jsonPath("$.pendingTodos[0].content").value("去XX群看看谁在找我"))
            .andExpect(jsonPath("$.memoryTotal").value(1))
            .andExpect(jsonPath("$.memoryByType.FACT").value(1))
            .andExpect(jsonPath("$.recentMemories[0].content").value("他喜欢蓝色"))
            .andExpect(jsonPath("$.serverTime").isNumber)
    }

    @Test
    fun `todo endpoints cancel bulk-clear and set or clear planned time`() {
        val todoService = TodoService(JsonFileTodoRepository(File.createTempFile("todos-api", ".json").absolutePath))
        val actionLog = ActionLog()
        val mvc = mvc(TodoController(todoService, actionLog))

        val a = todoService.add("去群里看看")
        val b = todoService.add("提醒他交房租")
        val epoch = System.currentTimeMillis() + 7_200_000L

        // 设置预计执行时间（带 epoch）
        mvc.perform(put("/api/todos/${a.id}/planned-at").param("epoch", epoch.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plannedAt").value(epoch))
            .andExpect(jsonPath("$.remainingPending").value(2))
        assertEquals(epoch, todoService.pending().first { it.id == a.id }.plannedAt, "服务层同步更新")

        // 不带 epoch = 清除
        mvc.perform(put("/api/todos/${a.id}/planned-at"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.plannedAt").isEmpty())
        assertNull(todoService.pending().first { it.id == a.id }.plannedAt)

        // 找不到 → 404
        mvc.perform(put("/api/todos/不存在的id/planned-at").param("epoch", epoch.toString()))
            .andExpect(status().isNotFound)

        // 取消一条（就地移出清单）
        mvc.perform(delete("/api/todos/${b.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cancelled").value(true))
            .andExpect(jsonPath("$.remainingPending").value(1))
        assertTrue(todoService.pending().none { it.id == b.id }, "取消后从清单消失")
        mvc.perform(delete("/api/todos/${b.id}")).andExpect(status().isNotFound) // 幂等：再取消 → 404

        // 清空全部未完成
        mvc.perform(delete("/api/todos"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.removed").value(1))
        assertTrue(todoService.pending().isEmpty())

        // 每一步都留了操作日志（谁在什么时候取消/清了什么）
        val log = actionLog.dump()
        assertTrue(log.contains("待办预计") || log.contains("清除待办预计"), "预计时间操作留痕：$log")
        assertTrue(log.contains("取消待办"), "取消留痕：$log")
        assertTrue(log.contains("清空未完成待办"), "清空留痕：$log")
    }
}

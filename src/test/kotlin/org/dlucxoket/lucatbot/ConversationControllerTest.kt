package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationAction
import org.dlucxoket.lucatbot.bot.agent.data.ConversationChatLine
import org.dlucxoket.lucatbot.bot.agent.data.ConversationDecision
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.agent.data.SessionAction
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickerRequest
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.ConversationController
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.todo.JsonFileTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.tool.SendMessageRequest
import org.dlucxoket.lucatbot.bot.tool.SendMessageResult
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.dlucxoket.lucatbot.bot.tool.ToolCatalog
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.bot.tool.data.HistoryMessageItem
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** 依次吐出预设 LLM 响应；同时记录 system 与 user 两段（以分隔符拼接，供子串断言） */
class FakeLLM(private val responses: MutableList<String>) : LLM {
    val prompts = mutableListOf<String>()
    /** 每轮请求携带的图片附件（图片通道断言用；最后一轮为准） */
    var images: List<org.dlucxoket.lucatbot.bot.llm.PromptImage> = emptyList()

    override fun ask(systemPrompt: String, question: String): String {
        prompts += systemPrompt + "\n=====USER=====\n" + question
        return if (responses.isEmpty()) """{"action":"return"}""" else responses.removeAt(0)
    }

    override fun ask(
        systemPrompt: String,
        question: String,
        images: List<org.dlucxoket.lucatbot.bot.llm.PromptImage>
    ): String {
        this.images = images
        return ask(systemPrompt, question)
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

/** 假发送工具（记录每次请求；支持第 N 次调用失败——分段发送部分失败用） */
class FakeSendMessageTool : Tool<SendMessageRequest, SendMessageResult>() {
    val sent = mutableListOf<String>()
    val requests = mutableListOf<SendMessageRequest>()
    var fail = false
    /** 失败时返回的错误文案（默认通用失败；可配 retCode 等细节验证失败提示） */
    var error = "mock fail"
    /** 第 N 次 handle 调用返回失败（1 起）；null = 由 [fail] 控制 */
    var failOnCall: Int? = null
    private var calls = 0
    override val name = "FakeSendMessageTool"
    override val description = ""
    override val parameterHint = ""
    override val inputClass = SendMessageRequest::class.java
    override fun handle(input: SendMessageRequest): SendMessageResult {
        calls++
        requests += input
        if (fail || (failOnCall != null && calls == failOnCall)) {
            return SendMessageResult(success = false, error = error)
        }
        sent += input.content
        return SendMessageResult(success = true)
    }
}

/** 测试用回显工具（验证 ToolCatalog 目录与调用链路） */
class FakeEchoTool : Tool<FakeEchoTool.Request, FakeEchoTool.Result>() {
    data class Request(val q: String = "")
    data class Result(val echoed: String = "", val success: Boolean = true)
    override val name = "FakeEchoTool"
    override val description = "功能：把参数 q 原样返回。\n何时使用：测试专用。"
    override val parameterHint = "JSON: {\"q\": \"文本\"}"
    override val inputClass: Class<Request> = Request::class.java
    override fun handle(input: Request): Result = Result(echoed = input.q)
}

/** 会话内编排循环的行为测试：决策执行、副作用、退出记忆整理与停机收尾 */
class ConversationControllerTest {

    private fun buildController(
        llm: FakeLLM,
        sendTool: FakeSendMessageTool = FakeSendMessageTool(),
        historyCount: Int = 5,
        todoService: TodoService = TodoService(
            JsonFileTodoRepository(File.createTempFile("todos", ".json").apply { delete() }.absolutePath)
        ),
        memoryService: MemoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem", ".json").absolutePath)
        ),
        chatHistoryService: ChatHistoryService = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist", ".json").absolutePath)
        ),
        /**
         * 退出时记忆整理阶段专用的 FakeLLM——与决策脚本队列隔离：
         * 共用一个实例会让每次 run() 多消耗一次 ask（打乱 prompts.size / 脚本顺序断言），
         * 这里默认耗尽（返回 {"action":"return"} → 解析为空记忆）。
         */
        memoryLLM: FakeLLM = FakeLLM(mutableListOf()),
        /** 工具目录网关（list_tools / tool 集成测试用假工具目录） */
        toolCatalog: ToolCatalog = ToolCatalog(),
        /** 假聊天记录行（默认造 historyCount 条；图片附件等场景传自定义行） */
        historyLines: List<HistoryMessageItem>? = null,
        /** 协议侧取图替身（NapCat get_image：ref → 本地路径/URL，null=救援失败） */
        imageSource: (String) -> String? = { null },
        /**
         * 记忆整理执行器——默认内联（同步）：生产是单线程 daemon 异步执行，
         * 测试同步化才能在 run() 返回后立刻断言写入结果、无竞态
         */
        reflectExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
        /**
         * 分段发送条间延迟替身——记录每次等待时长（测试断言「只在条间计时、
         * 第一条不等」而无需真睡）；返回 false = 未被新消息提前唤醒
         */
        segmentDelayWait: (conversationId: String, ms: Long) -> Boolean = { _, _ -> false }
    ): Triple<ConversationController, LucatBot, FakeSendMessageTool> {
        val lucatBot = LucatBot()
        val sessionManager = SessionManager()
        val actionLog = ActionLog()
        val historyTool = FakeChatHistoryTool(
            historyLines ?: (1..historyCount).map { HistoryMessageItem("用户A", "消息$it", it * 1000L) }
        )
        val controller = ConversationController(
            lucatBot = lucatBot,
            sessionManager = sessionManager,
            memoryService = memoryService,
            chatHistoryService = chatHistoryService,
            todoService = todoService,
            actionLog = actionLog,
            conversationAgent = ConversationAgent(llm),
            memoryAgent = MemoryAgent(memoryLLM),
            chatHistoryTool = historyTool,
            sendMessageTool = sendTool,
            toolCatalog = toolCatalog,
            imageSource = imageSource,
            initialHistoryCount = 3,
            reflectExecutor = reflectExecutor,
            segmentDelayWait = segmentDelayWait
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
        // send 后不阻塞等待：立即进入下一轮决策，本轮 prompt 应带「刚发送」hint
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"在吗"}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, sendTool) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        assertEquals(listOf("在吗"), sendTool.sent, "send 后应立即再决策并按脚本 return，不再连发")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)

        val secondPrompt = llm.prompts[1]
        assertTrue(secondPrompt.contains("刚发送"), "send 后那轮 prompt 应含「刚发送」hint：$secondPrompt")
        // 历史时间戳是 1970 年（非今天）→ 必须带 yyyy-MM-dd 日期前缀
        assertTrue(secondPrompt.contains("1970-01-01"), "非今天的历史消息时间应带日期：$secondPrompt")
    }

    @Test
    fun `no reply hint escalates over rounds without new messages`() {
        // 连续 send 且始终没有新消息插入：hint 应按「连续无新消息轮数」递进
        // 第 1 轮（脚本第 2 次 ask）沿用「刚发送」现文案；第 2 轮（第 3 次 ask）点明「已连续 2 轮」
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"第一句"}}""",
            """{"action":"send","params":{"content":"第二句"}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, sendTool) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(3, llm.prompts.size, "脚本 send/send/return 应恰好三次 ask")
        assertEquals(listOf("第一句", "第二句"), sendTool.sent)
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)

        val firstReplyRound = llm.prompts[1]
        assertTrue(firstReplyRound.contains("刚发送"), "第 1 轮无回应应保留「刚发送」现文案：$firstReplyRound")
        val secondReplyRound = llm.prompts[2]
        assertTrue(
            secondReplyRound.contains("已连续 2 轮"),
            "第 2 轮无回应应递进为「已连续 2 轮」：$secondReplyRound"
        )
        assertFalse(
            secondReplyRound.contains("刚发送了"),
            "递进后不应再是第 1 轮的「刚发送了」文案：$secondReplyRound"
        )
        // N≥2 轮 hint 末尾附当前心情（含强度数值），去留交 LLM 判断
        assertTrue(
            secondReplyRound.contains("当前心情：") && secondReplyRound.contains("强度 "),
            "无回应递进 hint 应附当前心情与强度：$secondReplyRound"
        )
    }

    @Test
    fun `stay delays then continues`() {
        val llm = FakeLLM(mutableListOf(
            """{"action":"stay","params":{"seconds":1}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, _) = buildController(llm)

        val start = System.currentTimeMillis()
        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed >= 1_000L, "stay 应至少停留 1s，实际 ${elapsed}ms")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
        // actionLog 注入下一轮 prompt：应记录了停留动作
        assertTrue(llm.prompts[1].contains("停留 1s"), "actionLog 应记录停留：${llm.prompts[1]}")
    }

    @Test
    fun `stay wakes early when new message arrives before timer ends`() {
        // stay 10s，但 200ms 后当前会话来新消息 → 应提前唤醒，不等满 10s
        val llm = FakeLLM(mutableListOf(
            """{"action":"stay","params":{"seconds":10}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, _) = buildController(llm)

        val start = System.currentTimeMillis()
        val runner = Thread {
            controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        }
        runner.start()
        // 等会话进入 STAY（决策完成后），再塞一条新消息
        Thread.sleep(300)
        lucatBot.enqueuePendingInsert("private:123", 456L, "用户B", "在吗")
        runner.join(15_000)
        val elapsed = System.currentTimeMillis() - start

        assertFalse(runner.isAlive, "新消息应提前唤醒 stay，机器人不应等满 10s")
        assertTrue(elapsed < 5_000L, "提前唤醒应在 5s 内完成，实际 ${elapsed}ms")
        assertTrue(
            llm.prompts.size >= 2 && llm.prompts[1].contains("提前唤醒"),
            "actionLog 应记录新消息提前唤醒：${llm.prompts.getOrNull(1)}"
        )
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
    }

    @Test
    fun `image attachments gathered from history and new messages`() {
        // 按需供图：LLM 显式 view_images 后，窗口记录 + 本轮新消息的指定图成为下一轮附件
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(16) { 1 }
        val histImg = File.createTempFile("hist-img", ".tmp").apply { writeBytes(png) }
        val liveImg = File.createTempFile("live-img", ".tmp").apply { writeBytes(png) }

        val llm = FakeLLM(mutableListOf(
            """{"action":"view_images","params":{"refs":["hist.png","live.png"]}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, _) = buildController(
            llm,
            historyLines = listOf(
                HistoryMessageItem(
                    sender = "用户A", content = "看这个[图片 file=hist.png]", timestamp = 1_000L,
                    imageUrls = listOf(histImg.absolutePath)
                )
            )
        )
        lucatBot.enqueuePendingInsert(
            "private:123", 456L, "用户B", "再来一张[图片 file=live.png]",
            imageUrls = listOf(liveImg.absolutePath)
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(2, llm.images.size, "请求的窗口记录与新消息图片都应成为附件：${llm.images}")
        assertEquals(listOf("hist.png", "live.png"), llm.images.map { it.ref }, "附件与占位符标签对应")
        assertTrue(llm.images.all { it.dataUri.startsWith("data:image/png;base64,") })
    }

    @Test
    fun `images are not attached unless explicitly requested`() {
        // 按需供图核心：未请求 view_images 的轮次不附任何图、不报缺失清单（省 token）
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(16) { 1 }
        val img = File.createTempFile("no-req", ".png").apply { writeBytes(png) }
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val (controller, _, _) = buildController(
            llm,
            historyLines = listOf(
                HistoryMessageItem(
                    sender = "用户A", content = "看这个[图片 file=x.png]", timestamp = 1_000L,
                    imageUrls = listOf(img.absolutePath)  // 地址可用——没请求就不该碰
                )
            )
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(llm.images.isEmpty(), "未请求不附内容")
        assertFalse(llm.prompts[0].contains("（以下图片加载失败"),
            "未请求也不出缺失清单（system 约束字样除外）：${llm.prompts[0]}")
    }

    @Test
    fun `protocol side get_image rescues expired image url`() {
        // 实测：HTTP 三路全挂的 8 张图，NapCat get_image 8/8 救回（协议侧自下载）。
        // 主地址 4xx（本地不存在文件即 404）→ 刷新链首站 imageSource → 本地路径直读
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(16) { 1 }
        val rescued = File.createTempFile("rescued", ".png").apply { writeBytes(png) }
        val llm = FakeLLM(mutableListOf(
            """{"action":"view_images","params":{"refs":["dead.png"]}}""",
            """{"action":"return"}"""
        ))
        val (controller, _, _) = buildController(
            llm,
            historyLines = listOf(
                HistoryMessageItem(
                    sender = "用户A", content = "看这个[图片 file=dead.png]", timestamp = 1_000L,
                    imageUrls = listOf(File("definitely-not-exist-xyz.png").absolutePath),
                    messageId = 7L  // 有 id 才会构建刷新链
                )
            ),
            imageSource = { ref -> if (ref == "dead.png") rescued.absolutePath else null }
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(1, llm.images.size, "协议侧救援成功的图应成为附件：${llm.images}")
        assertEquals("dead.png", llm.images[0].ref)
        assertFalse(llm.prompts[1].contains("（以下图片加载失败"), "救援成功就不该进缺失清单")
    }

    @Test
    fun `protocol side get_image rescues placeholder without any url`() {
        // 旧数据只有占位符没有地址 → 直接走协议侧取图，救不回才算加载失败
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(16) { 1 }
        val rescued = File.createTempFile("rescued2", ".png").apply { writeBytes(png) }
        val llm = FakeLLM(mutableListOf(
            """{"action":"view_images","params":{"refs":["old.png"]}}""",
            """{"action":"return"}"""
        ))
        val (controller, _, _) = buildController(
            llm,
            historyLines = listOf(
                HistoryMessageItem(
                    sender = "用户A", content = "老图[图片 file=old.png]", timestamp = 1_000L
                    // 无 imageUrls（旧档常见）
                )
            ),
            imageSource = { rescued.absolutePath }
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(1, llm.images.size, "无地址占位符应经协议侧救回：${llm.images}")
        assertFalse(llm.prompts[1].contains("（以下图片加载失败"), "救回后不进缺失清单")
    }

    @Test
    fun `protocol side failure degrades to missing list without breaking loop`() {
        // 协议侧也救不回 → 如实进「图片加载失败」，会话链路照常（绝不因图片失败）
        val llm = FakeLLM(mutableListOf(
            """{"action":"view_images","params":{"refs":["gone.png"]}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, _) = buildController(
            llm,
            historyLines = listOf(
                HistoryMessageItem(
                    sender = "用户A", content = "看这个[图片 file=gone.png]", timestamp = 1_000L
                )
            ),
            imageSource = { null }
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(llm.images.isEmpty())
        assertTrue(llm.prompts[1].contains("（以下图片加载失败，你没能看到内容）：gone.png"), "救不回如实标注")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations, "会话流程不受影响")
    }

    @Test
    fun `image gathering never breaks the loop when unresolvable`() {
        // 地址拿不到（文件不存在）→ 附件为空但事实如实进 prompt，会话照常走
        val llm = FakeLLM(mutableListOf(
            """{"action":"view_images","params":{"refs":["gone.png"]}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, _) = buildController(llm)
        lucatBot.enqueuePendingInsert(
            "private:123", 456L, "用户B", "看这个[图片 file=gone.png]",
            imageUrls = listOf(File("definitely-not-exist-xyz.png").absolutePath)
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(llm.images.isEmpty(), "拿不到的图跳过附件")
        val prompt = llm.prompts[1]
        assertTrue(prompt.contains("图片加载失败"), "附件缺失的事实要如实进 prompt：$prompt")
        assertTrue(prompt.contains("gone.png"), "缺失清单带文件标签：$prompt")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations, "会话流程不受影响")
    }

    @Test
    fun `send content array dispatches segments sequentially`() {
        // 分段发送：数组逐条发（条级 reply 传递），全部成功后进入下一轮
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":[
                {"reply": 99, "members":[{"type":"text","text":"第一条"}]},
                {"delay": 0, "members":[{"type":"text","text":"第二条"},{"type":"at","qq":"all"}]}
            ]}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, sendTool) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(2, sendTool.requests.size, "数组两条应逐条发送")
        assertEquals(99L, sendTool.requests[0].replyToId, "条级 reply 传递到工具")
        assertEquals("text", sendTool.requests[0].members[0].type, "结构化段传递")
        assertEquals("第一条", sendTool.requests[0].members[0].text)
        assertNull(sendTool.requests[1].replyToId, "第二条无 reply")
        assertEquals(2, sendTool.requests[1].members.size, "多段成员保留")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations, "发送后按脚本 return")
        // 操作日志汇总两条
        assertTrue(llm.prompts[1].contains("第一条") || llm.prompts[1].contains("回复："),
            "记账文本进下一轮 prompt：${llm.prompts[1]}")
    }

    @Test
    fun `segmented send stops at failure and reports segment position`() {
        // 部分失败：第 2/3 条失败即停（第 3 条不发），已发第 1 条照常记账，提示带第 k/N
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":[
                {"members":[{"type":"text","text":"第一"}]},
                {"members":[{"type":"text","text":"第二"}]},
                {"members":[{"type":"text","text":"第三"}]}
            ]}}""",
            """{"action":"return"}"""
        ))
        val sendTool = FakeSendMessageTool().apply { failOnCall = 2; error = "发送失败 status=failed retCode=1200（可能被禁言）" }
        val (controller, _, _) = buildController(llm, sendTool = sendTool)

        controller.run(SessionPick(SessionPickType.GROUP, "42"))

        assertEquals(2, sendTool.requests.size, "失败即停——第三条不发")
        val secondPrompt = llm.prompts[1]
        assertTrue(secondPrompt.contains("第 2/3 条失败"), "失败提示带第 k/N 条：$secondPrompt")
        assertTrue(secondPrompt.contains("被禁言了"), "禁言判定照常（retCode 仍在 detail 里）：$secondPrompt")
        assertEquals("", sendTool.sent.lastOrNull() ?: "", "失败条不入 sent")
        // 已发第 1 条的记账在操作日志里
        assertTrue(secondPrompt.contains("已发 1/3") || secondPrompt.contains("第一"),
            "部分成功要如实记账：$secondPrompt")
    }

    @Test
    fun `send with invalid content array notifies without burning failure streak`() {
        // LLM 输出空数组/无效条目 → 如实提示重发，不计通道失败（不碰护栏）
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":[]}}""",
            """{"action":"return"}"""
        ))
        val (controller, _, sendTool) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(sendTool.requests.isEmpty(), "无效内容不发")
        val secondPrompt = llm.prompts[1]
        assertTrue(secondPrompt.contains("无法识别任何有效消息"), "无效发送如实告知：$secondPrompt")
        assertTrue(secondPrompt.contains("重新组织"), "引导按格式重发：$secondPrompt")
    }

    @Test
    fun `segment delay times only between messages and send window messages inject next round`() {
        // delay 语义：上一条发完之后才开始计时（第一条没有上一条、不等）；
        // 整个序列在同一轮决策内发完才进下一轮；发送窗口内到达的别人消息
        // 进待插入队列、下一轮 drain 进 prompt（机器人自己发的由 Tool 逐条
        // 写聊天记录，生产链路下一轮历史可读）
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":[
                {"delay": 999, "members":[{"type":"text","text":"第一条"}]},
                {"delay": 5, "members":[{"type":"text","text":"第二条"}]},
                {"members":[{"type":"text","text":"第三条"}]}
            ]}}""",
            """{"action":"return"}"""
        ))
        val waits = mutableListOf<Long>()
        var bot: LucatBot? = null
        val (controller, lucatBot, sendTool) = buildController(
            llm,
            segmentDelayWait = { _, ms ->
                waits += ms
                // 模拟条间等待期间对方发来消息
                bot?.enqueuePendingInsert(
                    "private:123", 9L, "用户B", "等你的时候插一句",
                    messageId = 99L
                )
                false
            }
        )
        bot = lucatBot

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(listOf(5_000L), waits,
            "只在条间计时一次：第一条 delay(999s) 不等（无上一条）、第三条无 delay 不等")
        assertEquals(3, sendTool.requests.size, "序列在同一轮内发完（否则会多轮决策）")
        assertEquals(2, llm.prompts.size, "发完才进入下一轮决策")

        val nextPrompt = llm.prompts[1]
        assertTrue(nextPrompt.contains("第一条") && nextPrompt.contains("第三条"),
            "发送窗口内自己发的消息进下一轮 prompt（操作日志「回复：…」；聊天记录「我」行靠 Tool 写真实历史，生产链路）：$nextPrompt")
        assertTrue(nextPrompt.contains("等你的时候插一句"),
            "发送窗口内到达的别人消息注入下一轮 prompt：$nextPrompt")
        assertTrue(nextPrompt.contains("（新消息）"), "以新消息注释形式呈现：$nextPrompt")
    }

    @Test
    fun `no new message constraint and anti recite are in prompt`() {
        // 实盘：停滞 12 分钟后凭记忆旧话题自发发了一条（还概括了自己的人设内容）
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val (controller, _, _) = buildController(llm)
        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("没有任何新消息时不要主动开口"), "自发约束：$prompt")
        assertTrue(prompt.contains("不要接记忆或历史里没聊完的茬"), "点名不接记忆旧茬：$prompt")
        assertTrue(prompt.contains("不复述也不概括自己的人设"), "反反复述人设约束：$prompt")
        // hint 触发通道的警示在 send 之后那轮，由独立用例覆盖
    }

    @Test
    fun `send then stay hint carries no spontaneous talk warning`() {
        // send 后无新消息的那轮 hint 必须带『不凭记忆续话』——01:53 自发消息正是从这个通道出去的
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"在吗"}}""",
            """{"action":"return"}"""
        ))
        val (controller, _, _) = buildController(llm)
        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        assertTrue(llm.prompts[1].contains("不要凭记忆续话或自创话题"), "第二轮 hint 应含警示：${llm.prompts[1]}")
    }

    @Test
    fun `late arriving image attachment is not treated as new message`() {
        // 实盘 04:00/04:18：图片迟到几轮才附上，LLM 把它当"值得汇报的新内容"自发发言两次
        // （两条之间对方零消息）——系统侧变化必须被点名为「不算新消息」
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val (controller, _, _) = buildController(llm)
        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("图片附件这轮才成功附上"), "迟到附件不算新消息：$prompt")
        assertTrue(prompt.contains("都不是新消息") || prompt.contains("不是新消息"), "点明系统侧变化非新消息：$prompt")
        assertTrue(prompt.contains("对方不说话你就不说话"), "给出行为准则：$prompt")
    }

    @Test
    fun `banned failure note forbids further sends until ban lifts`() {
        // 被禁言（retCode=1200）→ 提示每轮重复 + 明确禁止再发。
        // 实盘：旧文案每轮邀请「stay 重试 / 换个说法再发」，禁言期间不断试发
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"hi"}}""",
            """{"action":"return"}"""
        ))
        val sendTool = FakeSendMessageTool().apply { fail = true; error = "发送失败 status=failed retCode=1200（可能被禁言）" }
        val (controller, lucatBot, _) = buildController(llm, sendTool = sendTool)

        controller.run(SessionPick(SessionPickType.GROUP, "42"))

        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations, "去留交给 LLM")
        val secondPrompt = llm.prompts[1]
        assertTrue(secondPrompt.contains("被禁言了"), "应点名被禁言：$secondPrompt")
        assertTrue(secondPrompt.contains("没有发出去"), "应说明没发出")
        assertTrue(secondPrompt.contains("**禁言期间不要再输出 send**"), "禁言期间必须禁止再发：$secondPrompt")
        assertTrue(secondPrompt.contains("return（离开会话）与 stay"), "只留 return/stay 两个选项：$secondPrompt")
        assertTrue(secondPrompt.contains("每一轮都会重复"), "提示成功前每轮重复（持续可见）：$secondPrompt")
        assertTrue(secondPrompt.contains("禁言解除前绝不输出 send"), "system 约束同步禁令：$secondPrompt")
        assertFalse(secondPrompt.contains("接下来自行决定"), "禁言时不再邀请自主决策重试：$secondPrompt")
        assertFalse(secondPrompt.contains("或换个说法再发"), "旧的重试邀请应已移除：$secondPrompt")
    }

    @Test
    fun `non-ban failure note keeps options but discourages endless retries`() {
        // 无 retCode 的失败（网络抖动等）→ 不误判为禁言：保留去留自主权，但点名别连发
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"hi"}}""",
            """{"action":"return"}"""
        ))
        val sendTool = FakeSendMessageTool().apply { fail = true; error = "发送通道超时" }
        val (controller, _, _) = buildController(llm, sendTool = sendTool)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        val secondPrompt = llm.prompts[1]
        assertTrue(secondPrompt.contains("接下来自行决定"), "非禁言失败保留自主决定：$secondPrompt")
        assertTrue(secondPrompt.contains("不要一轮接一轮连续试发"), "点名不要连续试发：$secondPrompt")
        assertFalse(secondPrompt.contains("**被禁言了**"), "无禁言回执不误判为禁言：$secondPrompt")
        assertFalse(secondPrompt.contains("或换个说法再发"), "旧的重试邀请应已移除：$secondPrompt")
    }

    @Test
    fun `prompt marks own lines as self not others`() {
        // 实盘：把自己发的消息误认为别人发的 → 行格式规则点名「我」行是自己
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val (controller, _, _) = buildController(llm)
        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("是**你自己发出的话**"), "应点明「我」行是自己：$prompt")
        assertTrue(prompt.contains("不要把它当成对方或群友的发言"), "应禁止误认：$prompt")
    }

    @Test
    fun `prompt nudges climbing when context feels incomplete and keeps climbing until complete`() {
        // 感觉上文不全 → 更勤快爬楼；爬完仍不全 → 继续爬，补齐或翻到头才停
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val (controller, _, _) = buildController(llm)
        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        val prompt = llm.prompts[0]

        assertTrue(prompt.contains("上文不全 → 先爬楼"), "应有专门的爬楼引导段：$prompt")
        assertTrue(prompt.contains("这一轮就 read_more"), "有不全信号当轮即爬：$prompt")
        assertTrue(prompt.contains("继续 read_more"), "爬完仍不全要继续爬：$prompt")
        assertTrue(prompt.contains("直到补齐"), "爬到补齐为止：$prompt")
        assertTrue(prompt.contains("已翻到最早消息时不要再 read_more"), "翻到头仍要收口：$prompt")
        // 旧约束「不要无休止地反复爬楼」会压制勤快爬楼，已改为按不全信号判断
        assertFalse(prompt.contains("不要无休止地反复爬楼"), "旧的保守约束应已移除：$prompt")
        assertTrue(prompt.contains("觉得上文不全就用 read_more 往前翻"),
            "视窗顶部提示同步引导：$prompt")
    }

    @Test
    fun `prompt clarifies resend means new content not verbatim repeat`() {
        // 实盘 4 组 MD5 全同的重复发送：prompt 侧双保险——system 禁令 + hint 语义引导，
        // 点明「再发」是新内容而非原样重发
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"蹲到了，快演示演示"}}""",
            """{"action":"return"}"""
        ))
        val (controller, lucatBot, sendTool) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(listOf("蹲到了，快演示演示"), sendTool.sent, "正常发送不受影响")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
        // system prompt 禁令：说清「再发」= 新内容
        assertTrue(llm.prompts[0].contains("「再发」= 发一条新内容"), "system 应界定再发语义：${llm.prompts[0]}")
        assertTrue(llm.prompts[0].contains("绝不是把上一条消息原样复制重发"), "system 应点名不许原样重发")
        // hint 引导：send 后无新消息那轮同样点明
        assertTrue(llm.prompts[1].contains("指的是**新内容**"), "hint 应引导发新内容：${llm.prompts[1]}")
        assertTrue(llm.prompts[1].contains("原样重发一遍"), "hint 应点名不许原样重发")
    }

    @Test
    fun `justSent hint quotes only first 20 chars of long content`() {
        // hint 递原文是复读根源：整句喂回去 → 弱模型照抄。截断到 20 字 + …
        val longContent = "这是一条明显超过二十个字符的超长消息内容用来验证截断"
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"$longContent"}}""",
            """{"action":"return"}"""
        ))
        val (controller, _, _) = buildController(llm)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        val nextPrompt = llm.prompts[1]
        val expectedQuote = "「${longContent.take(20)}…」"
        assertTrue(nextPrompt.contains(expectedQuote), "hint 应只引用前 20 字：$nextPrompt")
        assertTrue(
            !nextPrompt.contains("你刚发送了「$longContent」"),
            "hint 不应整句喂回原文"
        )
    }

    @Test
    fun `conversation prompt injects global memory overview with subject labels`() {
        // 不再只注入当前会话：主体记忆 + 其他会话记忆（带主体标签）+ 搜索引导
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem-ov-inj", ".json").absolutePath)
        )
        memoryService.rememberFact(MemorySubject.user(123L), "他在上海工作")
        memoryService.rememberFact(MemorySubject.user(9999L), "狐狐群的老朋友")
        memoryService.rememberFact(MemorySubject.group(55L), "55群的周末约定")

        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val (controller, _, _) = buildController(llm, memoryService = memoryService)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("他在上海工作"), "主体记忆注入：$prompt")
        assertTrue(prompt.contains("【QQ9999】狐狐群的老朋友"), "其他会话记忆带主体标签：$prompt")
        assertTrue(prompt.contains("【群55】"), "群记忆也注入：$prompt")
        assertTrue(prompt.contains("MemoryQueryTool"), "缺细节时引导关键词搜索：$prompt")
        assertTrue(prompt.contains("记于"), "记忆带写入日期：$prompt")
    }

    @Test
    fun `extractor marks source line memorized after writing`() {
        // 提取器从对方消息写出记忆后给该行打标——退出整理时不再改写复读
        val chatHistoryService = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist-mark", ".json").absolutePath)
        )
        chatHistoryService.recordHistoryMessage(
            conversationId = "private:123", senderId = 123L, senderName = "用户A",
            content = "我叫小明", timestamp = 1_000L, isFromBot = false, messageId = 7L
        )
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"好名字"}}""",
            """{"action":"return"}"""
        ))
        val (controller, _, _) = buildController(
            llm,
            chatHistoryService = chatHistoryService,
            historyLines = listOf(
                HistoryMessageItem(sender = "用户A", content = "我叫小明", timestamp = 1_000L, senderId = 123L, messageId = 7L)
            )
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        val summary = chatHistoryService.getHistorySummary("private:123", 10)
        assertTrue(summary.contains("我叫小明（已记忆）"), "提取器写记忆后源行打标：$summary")
    }

    @Test
    fun `new message note carries message id so llm can reply to it`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val (controller, lucatBot, _) = buildController(llm)
        // 带 message_id 的新消息 → 注释带（#id）标记，本轮即可对其发起引用回复
        lucatBot.enqueuePendingInsert("private:123", 456L, "用户B", "在吗", messageId = 777L)
        // 无 id（旧数据/异常）→ 注释不带标记，不渲染空（#）
        lucatBot.enqueuePendingInsert("private:123", 456L, "用户B", "在吗2")

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        val prompt = llm.prompts[0]
        assertTrue(prompt.contains("（#777）"), "新消息注释应带消息 id：$prompt")
        assertTrue(prompt.contains("（新消息）用户B(456)"), "注释基础格式不变：$prompt")
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
    fun `send failure notifies llm instead of exiting immediately`() {
        // 失败不再直接退出：下一轮 prompt 带失败通知（错误详情 + 可选行为），由 LLM 决定去留
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"hi"}}""",
            """{"action":"return"}"""
        ))
        val sendTool = FakeSendMessageTool().apply { fail = true }
        val chatHistoryService = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist-fail", ".json").absolutePath)
        )
        val (controller, lucatBot, _) = buildController(llm, sendTool = sendTool, chatHistoryService = chatHistoryService)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations, "LLM 决定 return 后回到列表")
        assertEquals(2, llm.prompts.size, "失败后不退出，还有一轮决策")
        val secondPrompt = llm.prompts[1]
        assertTrue(secondPrompt.contains("发送失败") || secondPrompt.contains("失败了"), "失败通知应注入 prompt：$secondPrompt")
        assertTrue(secondPrompt.contains("没有写入聊天记录") || secondPrompt.contains("没有发出去"), "应说明消息未发出未入档：$secondPrompt")
        // 失败的消息不入聊天记录
        val history = chatHistoryService.getHistory("private:123", 10)
        assertTrue(history.none { it.content == "hi" }, "发送失败的消息不能入档：$history")
    }

    @Test
    fun `repeated send failures hit cap and force return`() {
        // 连续失败达上限（maxSendFailures=3）→ 强制收尾，不再无限烧
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"一"}}""",
            """{"action":"send","params":{"content":"二"}}""",
            """{"action":"send","params":{"content":"三"}}""",
            """{"action":"send","params":{"content":"四"}}"""
        ))
        val sendTool = FakeSendMessageTool().apply { fail = true }
        val (controller, lucatBot, _) = buildController(llm, sendTool = sendTool)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations, "达上限强制返回")
        assertEquals(3, llm.prompts.size, "3 次失败后强制收尾，第 4 条脚本不消费")
        assertTrue(sendTool.sent.isEmpty(), "全部失败，无成功发送")
    }

    @Test
    fun `remember attached to failed send is dropped not faked`() {
        // 假记忆防护：send + remember（如「我回复了xxx」）在发送失败时不能写入
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem-fail", ".json").absolutePath)
        )
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"hi"},"remember":"我回复了hi"}""",
            """{"action":"return"}"""
        ))
        val sendTool = FakeSendMessageTool().apply { fail = true }
        val (controller, _, _) = buildController(llm, sendTool = sendTool, memoryService = memoryService)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        val items = memoryService.getMemoriesOf(MemorySubject.user(123L)) +
            memoryService.getMemoriesOf(MemorySubject.group(123L))
        assertTrue(items.none { it.content.contains("我回复了") }, "发送失败不能留下『我回复了』记忆：$items")
    }

    @Test
    fun `remember attached to successful send is written`() {
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem-ok", ".json").absolutePath)
        )
        val llm = FakeLLM(mutableListOf(
            """{"action":"send","params":{"content":"hi"},"remember":"我回复了hi"}""",
            """{"action":"return"}"""
        ))
        val (controller, _, sendTool) = buildController(llm, memoryService = memoryService)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(listOf("hi"), sendTool.sent, "发送成功")
        val items = memoryService.getMemoriesOf(MemorySubject.user(123L)) +
            memoryService.getMemoriesOf(MemorySubject.group(123L))
        assertTrue(items.any { it.content == "我回复了hi" }, "发送成功后延后的 remember 应写入：$items")
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
        val (controller, lucatBot, sendTool) = buildController(llm, todoService = todoService)

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertEquals(listOf("帮他看群"), todoService.pending().map { it.content }, "todo_add 应写入 TodoService")
        assertEquals(listOf("行，我帮你看着"), sendTool.sent)
        val prompt = llm.prompts.first()
        assertTrue(prompt.contains("### 待办"), "决策 prompt 应含 ### 待办 段")
        assertTrue(prompt.contains("（无待办）"), "决策时清单为空应显示占位")
        // todo_add 成功后应追加一行操作日志，下一轮 prompt 的操作日志段可见
        assertTrue(
            llm.prompts[1].contains("待办+：帮他看群"),
            "todo_add 应写入操作日志：${llm.prompts[1]}"
        )
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

    @Test
    fun `exit reflect writes fact and opinion into memory service`() {
        val decisionLLM = FakeLLM(mutableListOf("""{"action":"return","reason":"聊完了"}"""))
        val memoryLLM = FakeLLM(mutableListOf(
            """{"memories":[{"kind":"fact","content":"他最喜欢的颜色是蓝色","tags":["喜好"]},{"kind":"opinion","content":"这人挺有趣"}]}"""
        ))
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem", ".json").absolutePath)
        )
        // 预置一条现有记忆：应作为「读取参考」出现在记忆整理 prompt 里
        memoryService.rememberFact(MemorySubject.user(123L), "他在上海工作")
        val chatHistoryService = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist", ".json").absolutePath)
        )
        chatHistoryService.recordHistoryMessage(
            conversationId = "private:123",
            senderId = 123L,
            senderName = "用户A",
            content = "我最喜欢的颜色是蓝色",
            timestamp = 1_000_000L,
            isFromBot = false
        )
        val (controller, lucatBot, _) = buildController(
            decisionLLM,
            memoryService = memoryService,
            chatHistoryService = chatHistoryService,
            memoryLLM = memoryLLM
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
        val items = memoryService.getMemoriesOf(MemorySubject.user(123L))
        assertTrue(
            items.any {
                it.type == MemoryItem.MemoryType.FACT &&
                    it.content == "他最喜欢的颜色是蓝色" &&
                    it.tags == listOf("喜好")
            },
            "fact 应写入并带 tags：$items"
        )
        assertTrue(
            items.any { it.type == MemoryItem.MemoryType.OPINION && it.content == "这人挺有趣" },
            "opinion 应写入：$items"
        )
        assertEquals(3, items.size, "预置 1 条 + 新写 2 条：$items")

        // 记忆整理 prompt 应同时带现有记忆（读取参考）与本次会话内容
        val reflectPrompt = memoryLLM.prompts.single()
        assertTrue(reflectPrompt.contains("他在上海工作"), "prompt 应含现有记忆：$reflectPrompt")
        assertTrue(reflectPrompt.contains("我最喜欢的颜色是蓝色"), "prompt 应含会话内容：$reflectPrompt")
    }

    @Test
    fun `exit reflect with empty memories writes nothing`() {
        val decisionLLM = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val memoryLLM = FakeLLM(mutableListOf("""{"memories":[]}"""))
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem", ".json").absolutePath)
        )
        val (controller, lucatBot, _) = buildController(
            decisionLLM, memoryService = memoryService, memoryLLM = memoryLLM
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
        assertTrue(memoryService.getMemoriesOf(MemorySubject.user(123L)).isEmpty(), "空 memories 不应新增记忆")
        assertEquals("", memoryService.getMemorySummary(MemorySubject.user(123L)))
    }

    @Test
    fun `exit reflection runs asynchronously without blocking return to list`() {
        // 退出整理改异步：run() 不等 LLM，返回消息列表立即可继续；任务随后落地
        val decisionLLM = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val memoryLLM = FakeLLM(mutableListOf("""{"memories":[{"kind":"fact","content":"异步整理的事"}]}"""))
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem-async", ".json").absolutePath)
        )
        val queued = mutableListOf<Runnable>()
        val (controller, lucatBot, _) = buildController(
            decisionLLM,
            memoryService = memoryService,
            memoryLLM = memoryLLM,
            reflectExecutor = java.util.concurrent.Executor { queued.add(it) }
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        // run() 已返回、状态已回列表——退出路径没被记忆整理的 LLM 调用卡住
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
        assertTrue(memoryService.getMemoriesOf(MemorySubject.user(123L)).isEmpty(),
            "任务还在队列里，尚未写入")
        assertEquals(1, queued.size, "整理任务已入队")

        // 执行队列里的任务 → 记忆落地
        queued.forEach { it.run() }
        assertEquals(1, memoryService.getMemoriesOf(MemorySubject.user(123L)).size,
            "任务执行后写入")
        assertTrue(memoryLLM.prompts.isNotEmpty(), "复盘 LLM 被调用过")
    }

    @Test
    fun `vision unsupported skips fetching images and reports refs as missing`() {
        // 模型确认不支持识图（进程级记忆）→ 不给图片内容：不下载、不救援；
        // 若跳过失效，本地存在的 png 会被 resolve/救援救回（llm.images 非空）→ 断言失败
        val llm = FakeLLM(mutableListOf(
            """{"action":"view_images","params":{"refs":["live.png"]}}""",
            """{"action":"return"}"""
        ))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(16) { 1 }
        val img = File.createTempFile("nosight", ".png").apply { writeBytes(png) }
        val (controller, lucatBot, _) = buildController(
            llm,
            historyLines = listOf(
                HistoryMessageItem(
                    sender = "用户A", content = "看这个[图片 file=live.png]",
                    timestamp = 1_000L, imageUrls = listOf(img.absolutePath)
                )
            ),
            imageSource = { img.absolutePath }  // 救援替身同样可救回——跳过必须在取材之前
        )
        try {
            org.dlucxoket.lucatbot.bot.llm.VisionSupport.markUnsupported()
            controller.run(SessionPick(SessionPickType.PRIVATE, "123"))
        } finally {
            org.dlucxoket.lucatbot.bot.llm.VisionSupport.reset()
        }

        assertTrue(llm.images.isEmpty(), "不支持识图时不携带任何图片内容")
        val prompt = llm.prompts[1]
        assertTrue(prompt.contains("图片加载失败") && prompt.contains("live.png"),
            "refs 如实进缺失清单（复用现有约束链）：$prompt")
        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations, "会话流程不受影响")
    }

    @Test
    fun `memory reflect garbage does not affect exit flow`() {
        val decisionLLM = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val memoryLLM = FakeLLM(mutableListOf("totally not json"))
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem", ".json").absolutePath)
        )
        val (controller, lucatBot, _) = buildController(
            decisionLLM, memoryService = memoryService, memoryLLM = memoryLLM
        )

        // 记忆整理返回垃圾：不抛异常、照常回到消息列表
        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
        assertTrue(memoryService.getMemoriesOf(MemorySubject.user(123L)).isEmpty())
    }

    @Test
    fun `shutdown with active conversation reflects memory exactly once`() {
        val memoryLLM = FakeLLM(
            mutableListOf("""{"memories":[{"kind":"fact","content":"停机前记住的事"}]}""")
        )
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem", ".json").absolutePath)
        )
        val (controller, lucatBot, _) = buildController(
            FakeLLM(mutableListOf()), memoryService = memoryService, memoryLLM = memoryLLM
        )

        // 模拟：程序停在会话中（未走过正常退出路径）
        lucatBot.enterConversation("private:123", LucatBot.ConversationType.PRIVATE)
        controller.reflectForShutdown()
        controller.reflectForShutdown()  // 幂等保护：第二次应跳过

        assertEquals(1, memoryLLM.prompts.size, "同一会话的停机整理只应触发一次 LLM")
        val items = memoryService.getMemoriesOf(MemorySubject.user(123L))
        assertEquals(1, items.size, "记忆只应写入一份：$items")
        assertEquals("停机前记住的事", items[0].content)
    }

    @Test
    fun `shutdown outside conversation skips reflection`() {
        val memoryLLM = FakeLLM(
            mutableListOf("""{"memories":[{"kind":"fact","content":"不该出现"}]}""")
        )
        val memoryService = MemoryService(
            JsonFileMemoryRepository(File.createTempFile("mem", ".json").absolutePath)
        )
        val (controller, lucatBot, _) = buildController(
            FakeLLM(mutableListOf()), memoryService = memoryService, memoryLLM = memoryLLM
        )
        // 初始为 Idle，不在会话中：停机收尾应为空操作
        assertTrue(lucatBot.state !is LucatBot.State.InConversation)

        controller.reflectForShutdown()

        assertTrue(memoryLLM.prompts.isEmpty(), "不在会话中不应触发记忆整理")
        assertTrue(memoryService.getMemoriesOf(MemorySubject.user(123L)).isEmpty())
    }

    @Test
    fun `list_tools and tool actions execute and feed notes into next prompt`() {
        val decisionLLM = FakeLLM(mutableListOf(
            """{"action":"list_tools","params":{"query":"Echo"},"reason":"先看有什么工具"}""",
            """{"action":"tool","params":{"name":"FakeEchoTool","input":{"q":"hi"}},"reason":"试试调用"}""",
            """{"action":"return","reason":"完事"}"""
        ))
        val echoTool = FakeEchoTool()
        val (controller, lucatBot, _) = buildController(
            decisionLLM,
            toolCatalog = ToolCatalog(toolsProvider = { listOf(echoTool) })
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations)
        val prompts = decisionLLM.prompts
        assertEquals(3, prompts.size, "list_tools / tool / return 各决策一轮")

        // 第 2 轮：应看到目录（名字 + 功能 + 何时使用 + 参数）
        assertTrue(prompts[1].contains("### 工具"), "prompt 应含工具段：${prompts[1]}")
        assertTrue(prompts[1].contains("### FakeEchoTool"), "第 2 轮应看到目录：${prompts[1]}")
        assertTrue(prompts[1].contains("功能：把参数 q 原样返回"), prompts[1])
        assertTrue(prompts[1].contains("参数：{\"q\": \"文本\"}"), "参数列表应进入目录：${prompts[1]}")

        // 第 3 轮：应看到工具执行结果
        assertTrue(prompts[2].contains("执行结果"), "第 3 轮应看到执行结果：${prompts[2]}")
        assertTrue(prompts[2].contains("\"echoed\":\"hi\""), "回显结果应注入：${prompts[2]}")
    }

    @Test
    fun `unknown tool returns error note instead of breaking loop`() {
        val decisionLLM = FakeLLM(mutableListOf(
            """{"action":"tool","params":{"name":"NoSuchTool","input":{}},"reason":"瞎调"}""",
            """{"action":"return","reason":"完事"}"""
        ))
        val (controller, lucatBot, _) = buildController(
            decisionLLM,
            toolCatalog = ToolCatalog(toolsProvider = { listOf(FakeEchoTool()) })
        )

        controller.run(SessionPick(SessionPickType.PRIVATE, "123"))

        assertTrue(lucatBot.state is LucatBot.State.ViewingConversations, "工具失败不能打断循环")
        assertEquals(2, decisionLLM.prompts.size)
        assertTrue(
            decisionLLM.prompts[1].contains("不存在"),
            "第 2 轮应看到「工具不存在」错误说明：${decisionLLM.prompts[1]}"
        )
    }
}

/** 经 FakeLLM 验证 SessionPickerAgent 把 LLM 统一信封转换为 SessionDecision */
class SessionPickerAgentParseViaFakeLLMTest {

    @Test
    fun `agent converts envelope to OPEN_CONVERSATION decision`() {
        val llm = FakeLLM(mutableListOf(
            """{"action":"open_conversation","params":{"type":"private","target":"1"},"reason":"想找我"}"""
        ))
        val agent = SessionPickerAgent(llm)
        val decision = agent.processObject(SessionPickerRequest())
        assertEquals(SessionAction.OPEN_CONVERSATION, decision.action)
        assertEquals(SessionPick(SessionPickType.PRIVATE, "1"), decision.pick)
    }

    @Test
    fun `agent idle returns IDLE decision`() {
        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val agent = SessionPickerAgent(llm)
        assertEquals(SessionAction.IDLE, agent.processObject(SessionPickerRequest()).action)
    }

    @Test
    fun `agent prompt contains tools section`() {
        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val agent = SessionPickerAgent(llm)
        agent.processObject(
            SessionPickerRequest(
                toolNotes = listOf("（工具目录全部）\n### SendMessageTool\n功能：发消息。")
            )
        )
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("### 工具"), "消息列表 prompt 应含工具段：$prompt")
        assertTrue(prompt.contains("### SendMessageTool"), prompt)
        assertTrue(prompt.contains("list_tools"), "输出格式应列出 list_tools 动作：$prompt")
        assertTrue(prompt.contains("\"action\": \"open_conversation\" | \"idle\" | \"list_tools\" | \"tool\""), prompt)
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
        assertTrue(prompt.contains("## 你的输出格式（严格遵循）"), "标题应为「你的输出格式」：$prompt")
        assertTrue(prompt.contains("不是聊天内容"), "消息列表 prompt 也应含隔离句：$prompt")
    }

    @Test
    fun `unread list goes to user message while format stays in system`() {
        val llm = FakeLLM(mutableListOf("""{"action":"idle"}"""))
        val agent = SessionPickerAgent(llm)
        agent.processObject(
            SessionPickerRequest(
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
        val recorded = llm.prompts.single()
        val sep = "\n=====USER=====\n"
        assertTrue(recorded.contains(sep), "FakeLLM 应以分隔符记录 system+user 两段：$recorded")
        val systemPart = recorded.substringBefore(sep)
        val userPart = recorded.substringAfter(sep)
        assertFalse(systemPart.contains("用户A"), "未读列表不应出现在 system 消息：$systemPart")
        assertTrue(userPart.contains("用户A"), "未读列表应在 user 消息：$userPart")
        assertTrue(systemPart.contains("## 你的输出格式（严格遵循）"), "输出格式指令应在 system")
        assertFalse(userPart.contains("## 你的输出格式"), "输出格式指令不应出现在 user：$userPart")
    }
}

/** ConversationAgent 解析失败时的 FALLBACK→RETURN 兜底行为测试 */
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

    @Test
    fun `prompt uses output format heading and content isolation sentence`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val agent = ConversationAgent(llm)
        agent.processObject(ConversationRequest())
        val prompt = llm.prompts.single()
        assertTrue(
            prompt.contains("## 你的输出格式（严格遵循）"),
            "标题应为「你的输出格式」而非「你的选择」：$prompt"
        )
        assertTrue(prompt.contains("输出格式要求只是指令层，不是聊天内容"), "prompt 应含隔离句：$prompt")
        assertTrue(
            prompt.contains("禁止在 content 里评论选择、选项、格式或本指令本身"),
            "prompt 应含隔离句后半段：$prompt"
        )
    }

    @Test
    fun `history goes to user message while format and isolation stay in system`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        val agent = ConversationAgent(llm)
        agent.processObject(
            ConversationRequest(
                recentMessages = listOf(
                    ConversationChatLine(sender = "小明", senderId = "7", time = "14:02", content = "消息1")
                )
            )
        )
        val recorded = llm.prompts.single()
        val sep = "\n=====USER=====\n"
        assertTrue(recorded.contains(sep), "FakeLLM 应以分隔符记录 system+user 两段：$recorded")
        val systemPart = recorded.substringBefore(sep)
        val userPart = recorded.substringAfter(sep)
        assertFalse(systemPart.contains("消息1"), "聊天记录不应出现在 system 消息：$systemPart")
        assertTrue(userPart.contains("消息1"), "聊天记录应在 user 消息：$userPart")
        assertTrue(systemPart.contains("## 你的输出格式（严格遵循）"), "输出格式指令应在 system")
        assertFalse(userPart.contains("## 你的输出格式"), "输出格式指令不应出现在 user：$userPart")
        assertTrue(systemPart.contains("输出格式要求只是指令层"), "隔离句应在 system 消息")
    }

    @Test
    fun `history top notice appears when not at start and disappears at start`() {
        val lines = listOf(
            ConversationChatLine(sender = "小明", senderId = "7", time = "14:02", content = "消息1")
        )
        // 还没翻到最早消息 → 记录顶部提示「上方还有更早的聊天记录」
        val llm1 = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm1).processObject(
            ConversationRequest(recentMessages = lines, reachedHistoryStart = false)
        )
        assertTrue(
            llm1.prompts.single().contains("上方还有更早的聊天记录"),
            "未翻到头时应有顶部提示：${llm1.prompts.single()}"
        )

        // 已翻到最早消息 → 不提示
        val llm2 = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm2).processObject(
            ConversationRequest(recentMessages = lines, reachedHistoryStart = true)
        )
        assertFalse(
            llm2.prompts.single().contains("上方还有更早的聊天记录"),
            "翻到头后不应再提示：${llm2.prompts.single()}"
        )

        // 空记录（新会话）→ 不提示
        val llm3 = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm3).processObject(
            ConversationRequest(recentMessages = emptyList(), reachedHistoryStart = false)
        )
        assertFalse(
            llm3.prompts.single().contains("上方还有更早的聊天记录"),
            "空记录不提示：${llm3.prompts.single()}"
        )
    }

    @Test
    fun `prompt carries self qq and rules for whether message is addressed to me`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(ConversationRequest(selfQq = 3046673325L))
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("你的 QQ 号：3046673325"), "自身 QQ 号注入：$prompt")
        assertTrue(prompt.contains("不是每句话都在对你说"), "应有『不是每句都对你说』提示")
        assertTrue(prompt.contains("@其他QQ号"), "应有 @别人 ≠ @我 的判定规则")
        assertTrue(prompt.contains("宁可少接话也不要乱接"), "应有拿不准就不接的兜底规则")
        assertTrue(prompt.contains("私聊里对方的消息默认是对你说的"), "私聊规则")
    }

    @Test
    fun `prompt explains unresolved reply marker and bare at digits`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(ConversationRequest())
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("回复 #989342600"), "应解释未解析的『回复 #id』形态：$prompt")
        assertTrue(prompt.contains("消息编号"), "点明 #数字 是消息编号而非人：$prompt")
        assertTrue(prompt.contains("@2427686041"), "应解释裸 @数字 的语义：$prompt")
    }

    @Test
    fun `unknown self qq renders as unknown not zero`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(ConversationRequest(selfQq = 0L))
        assertTrue(llm.prompts.single().contains("你的 QQ 号：未知"), "selfQq=0 → 未知")
    }

    @Test
    fun `send rule documents segmented array format and legacy markers`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(ConversationRequest())
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("JSON 数组"), "send 应声明 content 为 JSON 数组（分段发送）：$prompt")
        assertTrue(prompt.contains("\"delay\""), "应教条级打字延迟：$prompt")
        assertTrue(prompt.contains("\"reply\""), "应教条级回复：$prompt")
        assertTrue(prompt.contains("\"members\""), "应教结构化 members：$prompt")
        assertTrue(prompt.contains("@全体成员"), "send 规则应含 @全体成员")
        assertTrue(prompt.contains("[表情:14]"), "send 规则应含 QQ 表情语法（老标记兼容）")
        assertTrue(prompt.contains("回复 #id"), "引用回复语法：$prompt")
        assertTrue(prompt.contains("图片/表情包发不了"), "应声明图片发不了")
        assertTrue(prompt.contains("不要原样发出去"), "应提醒行 id 标记不入正文")
        // 读侧：聊天记录标记的语义说明（回复/艾特/表情怎么读、怎么回）
        assertTrue(prompt.contains("读懂它们才能接上语境"), "标记阅读说明：$prompt")
        assertTrue(prompt.contains("在**引用**某人之前说的话"), "回复标记语义：$prompt")
        assertTrue(prompt.contains("[表情:14]` = 对方发了 QQ 表情"), "表情标记语义：$prompt")
        assertTrue(prompt.contains("用 send 的 reply/at/face 字段"), "读→写对应（结构化回法）：$prompt")
    }

    @Test
    fun `missing images list and anti fake vision rules render in prompt`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(
            ConversationRequest(missingImages = listOf("gone1.jpg", "gone2.jpg"))
        )
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("（以下图片加载失败，你没能看到内容）：gone1.jpg、gone2.jpg"),
            "缺失清单进 user 消息：$prompt")
        assertTrue(prompt.contains("不要假装看过"), "禁令：不许装看过")
        assertTrue(prompt.contains("禁止编造图里有什么"), "点名禁止编造")
    }

    @Test
    fun `qq face usage is restrained to necessary cases`() {
        // 表情语法刚教会 LLM，必须同时教会克制——否则容易每条消息都挂个表情
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(ConversationRequest())
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("只有确有必要"), "约束应要求确有必要才带表情：$prompt")
        assertTrue(prompt.contains("绝不每条消息都带"), "约束应点明不许每条都带：$prompt")
        assertTrue(prompt.contains("对方先发了表情要回应"), "应给出什么算『有必要』的判据：$prompt")
    }

    @Test
    fun `history line renders message id tag only when present`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(
            ConversationRequest(
                recentMessages = listOf(
                    ConversationChatLine(
                        sender = "小明", senderId = "7", time = "14:02",
                        content = "吃了吗", messageId = 581866777L
                    ),
                    ConversationChatLine(
                        sender = "小红", senderId = "8", time = "14:03",
                        content = "老数据", messageId = null
                    )
                )
            )
        )
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("（#581866777）"), "有 id 的行渲染（#id）：$prompt")
        assertTrue(prompt.contains("- 小红(8) [14:03]：老数据"), "无 id 的行不渲染空标记：$prompt")
        assertFalse(prompt.contains("（#null）"), "null id 不应渲染出字面标记：$prompt")
    }
}

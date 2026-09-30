package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.MemoryReflector
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 记忆写入判重——防「记忆被反复记录」
 *
 * 三个写入口的重复场景：
 * - MemoryExtractor 每次 send 都对「对方最后一条消息」重新正则提取（同一句反复记）
 * - 决策 remember 可多轮输出同一句
 * - 退出整理的 LLM 未必严格去重（且判重视野曾经只有 5/3/5/3 条）
 */
class MemoryDedupeTest {

    private fun service() = MemoryService(
        JsonFileMemoryRepository(File.createTempFile("mem-dedupe", ".json").absolutePath)
    )

    @Test
    fun `remembering same fact twice writes once`() {
        val s = service()
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(1L)).size, "同内容重复写入应被吞掉")
    }

    @Test
    fun `whitespace and case variations count as same memory`() {
        val s = service()
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        s.rememberFact(MemorySubject.user(1L), "他喜欢 猫")
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫 ")  // 尾随空白
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(1L)).size, "归一化后视为同一条")
    }

    @Test
    fun `same content under different kind is still duplicate`() {
        val s = service()
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        s.rememberEvent(MemorySubject.user(1L), "他喜欢猫")
        s.rememberOpinion(MemorySubject.user(1L), "他喜欢猫")
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(1L)).size, "同内容换 kind 也是重复")
    }

    @Test
    fun `different contents are all kept`() {
        val s = service()
        s.rememberFact(MemorySubject.user(1L), "他喜欢猫")
        s.rememberFact(MemorySubject.user(1L), "他喜欢狗")
        assertEquals(2, s.getMemoriesOf(MemorySubject.user(1L)).size)
    }

    @Test
    fun `same content under different subjects both kept`() {
        val s = service()
        s.rememberFact(MemorySubject.user(7L), "他喜欢猫")
        s.rememberFact(MemorySubject.group(7L), "他喜欢猫")
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(7L)).size, "键空间隔离不互相吞")
        assertEquals(1, s.getMemoriesOf(MemorySubject.group(7L)).size)
    }

    @Test
    fun `saveAll filters batch-internal and existing duplicates`() {
        val s = service()
        s.rememberFact(MemorySubject.user(2L), "已有记忆")

        // 批内互重 + 与已有重复 + 新内容
        s.saveAll(listOf(
            item("他喜欢猫"),
            item("他喜欢猫"),   // 批内重复
            item("已有记忆"),   // 与已有重复
            item("新的记忆")    // 唯一新增
        ))
        val contents = s.getMemoriesOf(MemorySubject.user(2L)).map { it.content }.toSet()
        assertEquals(setOf("已有记忆", "他喜欢猫", "新的记忆"), contents)
    }

    @Test
    fun `memory extractor replayed by repeated sends writes once`() {
        // 模拟：bot 连发多条、对方最后一条消息没变 → 同一句被提取多次
        val s = service()
        val subject = MemorySubject.user(3L)
        repeat(3) {
            s.saveAll(org.dlucxoket.lucatbot.bot.memory.MemoryExtractor.extract(subject, "我叫小明"))
        }
        assertEquals(1, s.getMemoriesOf(subject).size, "同一句被反复提取只能留一条")
    }

    @Test
    fun `blank content is never written`() {
        val s = service()
        s.saveAll(listOf(item("  "), item("正常")))
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(2L)).size)
    }

    private fun item(content: String) = MemoryItem(
        userId = 2L, subjectType = MemorySubject.Type.USER,
        type = MemoryItem.MemoryType.FACT, content = content
    )

    // ------------------------------------------------------------------
    // 「已记忆」标记（防退出整理把同一段对话改写一遍又记一遍）
    // ------------------------------------------------------------------

    @Test
    fun `markMemorized annotates summary and is idempotent`() {
        val f = Fixture()
        f.chat.recordHistoryMessage(
            conversationId = "private:5", senderId = 5L, senderName = "甲",
            content = "我叫小明", timestamp = 1_000L, isFromBot = false, messageId = 42L
        )
        // messageId 精确定位
        f.chat.markMemorized("private:5", 42L, "我叫小明")
        assertTrue(f.chat.getHistorySummary("private:5", 10).contains("我叫小明（已记忆）"),
            "摘要行尾带（已记忆）：${f.chat.getHistorySummary("private:5", 10)}")
        // 幂等 + 无目标不动
        f.chat.markMemorized("private:5", 42L, "我叫小明")
        f.chat.markMemorized("private:5", null, "不存在的内容")
        assertEquals(1, f.chat.getHistory("private:5", 10).size, "重复打标不产生新行")
    }

    @Test
    fun `markWindowMemorized marks non bot lines only`() {
        val f = Fixture()
        f.chat.recordHistoryMessage(
            conversationId = "private:6", senderId = 6L, senderName = "甲",
            content = "第一句", timestamp = 1_000L, isFromBot = false
        )
        f.chat.recordHistoryMessage(
            conversationId = "private:6", senderId = 0L, senderName = "bot",
            content = "回你", timestamp = 2_000L, isFromBot = true
        )
        f.chat.markWindowMemorized("private:6", 30)
        val summary = f.chat.getHistorySummary("private:6", 10)
        assertTrue(summary.contains("第一句（已记忆）"), "对方消息被打标：$summary")
        assertFalse(summary.contains("回你（已记忆）"), "机器人发言不是记忆素材，不打标：$summary")
    }

    @Test
    fun `memory summary lines carry write dates`() {
        val repo = JsonFileMemoryRepository(File.createTempFile("mem-date", ".json").absolutePath)
        val s = MemoryService(repo)
        s.rememberFact(MemorySubject.user(3L), "他叫小明")
        // 一年前写入的记忆 → 带年份，LLM 才知道是旧事
        repo.save(MemoryItem(
            userId = 3L, subjectType = MemorySubject.Type.USER,
            type = MemoryItem.MemoryType.FACT, content = "一年前的旧事",
            createdAt = java.time.ZoneId.of("Asia/Shanghai").let { z ->
                java.time.LocalDate.of(java.time.LocalDate.now(z).year - 1, 6, 1)
                    .atStartOfDay(z).toInstant().toEpochMilli()
            }
        ))

        val summary = s.getMemorySummary(MemorySubject.user(3L))
        assertTrue(summary.contains("（记于"), "记忆行带写入日期：$summary")
        assertTrue(summary.contains("- 一年前的旧事（记于"), "旧记忆带日期前缀：$summary")
        assertTrue(
            summary.lines().any { it.contains("一年前的旧事") && Regex("""记于\d{4}-\d{2}-\d{2}）""").containsMatchIn(it) },
            "跨年记忆用 yyyy-MM-dd：$summary"
        )
        assertTrue(summary.contains("他叫小明（记于"), "当年记忆用 MM-dd：$summary")
    }

    private class Fixture {
        val chat = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist-mark", ".json").absolutePath)
        )
    }

    @Test
    fun `reflector marks window lines as memorized after writing`() {
        val f = Fixture()
        f.chat.recordHistoryMessage(
            conversationId = "private:7", senderId = 7L, senderName = "甲",
            content = "我最喜欢的颜色是蓝色", timestamp = 1_000L, isFromBot = false
        )
        val memSvc = MemoryService(JsonFileMemoryRepository(File.createTempFile("mem-mark", ".json").absolutePath))
        val llm = FakeLLM(mutableListOf("""{"memories":[{"kind":"fact","content":"他最喜欢蓝色","tags":[]}]}"""))
        val reflector = MemoryReflector(
            memoryAgent = MemoryAgent(llm),
            memoryService = memSvc,
            chatHistoryService = f.chat,
            actionLog = ActionLog()
        )

        reflector.reflect("private:7")

        assertEquals(1, memSvc.getMemoriesOf(MemorySubject.user(7L)).size, "整理写入一条")
        assertTrue(
            f.chat.getHistorySummary("private:7", 10).contains("（已记忆）"),
            "写入成功后窗口行打标：${f.chat.getHistorySummary("private:7", 10)}"
        )
    }

    // ------------------------------------------------------------------
    // 退出整理判重视野：已有记忆要让 LLM 看得见（默认上限太小会漏判重）
    // ------------------------------------------------------------------

    @Test
    fun `summary default caps stay small but custom limits expose all`() {
        val s = service()
        repeat(7) { s.rememberFact(MemorySubject.user(9L), "事实$it") }

        val default = s.getMemorySummary(MemorySubject.user(9L))
        assertEquals(5, default.lines().count { it.startsWith("- ") }, "默认上限 5 条（控 prompt 体积）")

        val wide = s.getMemorySummary(MemorySubject.user(9L), factLimit = 50)
        assertEquals(7, wide.lines().count { it.startsWith("- ") }, "调大上限后全部可见")
    }

    @Test
    fun `reflector sees all existing memories for dedup`() {
        // 退出整理注入的现有记忆应含全部已记事实——LLM 看得见才不会重记
        val s = service()
        repeat(6) { s.rememberFact(MemorySubject.user(11L), "已有事实${'A' + it}") }

        val llm = FakeLLM(mutableListOf("""{"memories":[]}"""))
        val reflector = MemoryReflector(
            memoryAgent = MemoryAgent(llm),
            memoryService = s,
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist-reflect", ".json").absolutePath)
            ),
            actionLog = ActionLog()
        )
        reflector.reflect("private:11")

        val prompt = llm.prompts.single()
        repeat(6) { i ->
            val name = "已有事实${'A' + i}"
            assertTrue(prompt.contains(name), "第 $i 条已有记忆应出现在整理 prompt：$prompt")
        }
    }

    // ------------------------------------------------------------------
    // 写入前关键词查库复核（阶段2——拦改写式重复，精确判重拦不住的）
    // ------------------------------------------------------------------

    private fun reflectorWith(s: MemoryService, llm: FakeLLM): MemoryReflector = MemoryReflector(
        memoryAgent = MemoryAgent(llm),
        memoryService = s,
        chatHistoryService = ChatHistoryService(
            JsonFileChatHistoryRepository(File.createTempFile("hist-kw", ".json").absolutePath)
        ),
        actionLog = ActionLog()
    )

    @Test
    fun `keyword review filters reworded duplicate before write`() {
        val s = service()
        s.rememberFact(MemorySubject.user(20L), "他最喜欢的颜色是蓝色")

        // 阶段1 产出改写式候选（带关键词）；阶段2 复核判为重复 → 输出空
        val llm = FakeLLM(mutableListOf(
            """{"memories":[{"kind":"fact","content":"他喜欢的颜色是蓝的","keywords":["颜色","蓝色"]}]}""",
            """{"memories":[]}"""
        ))
        reflectorWith(s, llm).reflect("private:20")

        assertEquals(2, llm.prompts.size, "有关键词命中 → 触发阶段2复核")
        assertTrue(llm.prompts[0].contains("keywords"), "阶段1要求给检索关键词：${llm.prompts[0]}")
        assertTrue(llm.prompts[1].contains("【候选记忆】"), "复核走独立 prompt：${llm.prompts[1]}")
        assertTrue(llm.prompts[1].contains("他喜欢的颜色是蓝的"), "复核 prompt 带候选：${llm.prompts[1]}")
        assertTrue(llm.prompts[1].contains("他最喜欢的颜色是蓝色"), "复核 prompt 带关键词命中的已有记忆")
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(20L)).size,
            "改写式重复被拦下，只留原有记忆")
    }

    @Test
    fun `keyword review skipped when zero hits`() {
        val s = service()
        val llm = FakeLLM(mutableListOf(
            """{"memories":[{"kind":"fact","content":"他搬去了杭州","keywords":["杭州"]}]}"""
        ))
        reflectorWith(s, llm).reflect("private:21")

        assertEquals(1, llm.prompts.size, "查库零命中不触发复核（省一次 LLM 调用）")
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(21L)).size, "候选直接写入")
    }

    @Test
    fun `phase2 review failure conservatively writes candidates`() {
        // 复核轮解析失败 → degraded → 保守放行：复核环节故障绝不吞掉记忆，
        // 写入侧 MemoryService 精确判重仍是最后一道防线
        val s = service()
        s.rememberFact(MemorySubject.user(22L), "他最喜欢的颜色是蓝色")
        val llm = FakeLLM(mutableListOf(
            """{"memories":[{"kind":"fact","content":"他喜欢的颜色是蓝的","keywords":["颜色"]}]}""",
            "totally not json"
        ))
        reflectorWith(s, llm).reflect("private:22")

        assertEquals(2, s.getMemoriesOf(MemorySubject.user(22L)).size,
            "复核失败放行候选（内容与已有不同，精确判重不拦）")
    }

    @Test
    fun `phase2 all-duplicate verdict writes nothing`() {
        // 阶段2 明确输出「全部重复」→ 一条都不写
        val s = service()
        s.rememberFact(MemorySubject.user(23L), "他最喜欢的颜色是蓝色")
        val llm = FakeLLM(mutableListOf(
            """{"memories":[{"kind":"fact","content":"他喜欢的颜色是蓝的","keywords":["颜色"]}]}""",
            """{"memories":[]}"""
        ))
        reflectorWith(s, llm).reflect("private:23")

        assertEquals(1, s.getMemoriesOf(MemorySubject.user(23L)).size,
            "复核判全重复 → 不新增")
    }

    @Test
    fun `reflector persists keywords as searchable tags for all kinds`() {
        // 落库的关键词（tags）才是 search 命中的另一半——而 prompt 让 tags 可省略、
        // LLM 常给空；keywords（必给 5~10 个）必须转正落库，且 opinion/event
        // 的写入口原本连 tags 参数都没有（用户实盘：库里有些记忆没有关键词）
        val llm = FakeLLM(mutableListOf(
            """{"memories":[
                {"kind":"fact","content":"他最喜欢蓝色","tags":["喜好"],"keywords":["蓝色","颜色","偏好"]},
                {"kind":"opinion","content":"这人挺有趣","keywords":["性格","有趣"]},
                {"kind":"event","content":"约好周五打球","keywords":["打球","约定","周五"]}
            ]}"""
        ))
        val s = service()
        reflectorWith(s, llm).reflect("private:40")

        val items = s.getMemoriesOf(MemorySubject.user(40L)).associateBy { it.type }
        assertEquals(3, items.size, "三类各写一条（零命中不触发复核）")
        assertEquals(setOf("喜好", "蓝色", "颜色", "偏好"),
            items[MemoryItem.MemoryType.FACT]!!.tags.toSet(), "fact = tags ∪ keywords")
        assertEquals(setOf("性格", "有趣"),
            items[MemoryItem.MemoryType.OPINION]!!.tags.toSet(), "opinion 无 tags 时 keywords 全落库")
        assertEquals(setOf("打球", "约定", "周五"),
            items[MemoryItem.MemoryType.EVENT]!!.tags.toSet(), "event 同样带关键词")
    }

    @Test
    fun `review feeds back at most maxReviewHits newest matches`() {
        // 关键词扩容后实盘命中曾达 77 条，全量回喂会把复核 prompt 撑爆——
        // 截取最新 30 条（search 按写入时间倒序）
        val s = service()
        repeat(40) { s.rememberFact(MemorySubject.user(50L), "他养猫第 $it 只")

        }
        val llm = FakeLLM(mutableListOf(
            """{"memories":[{"kind":"fact","content":"他又养了一只猫","keywords":["猫"]}]}""",
            """{"memories":[]}"""
        ))
        reflectorWith(s, llm).reflect("private:50")

        assertEquals(2, llm.prompts.size, "有命中 → 触发复核")
        // FakeLLM 记录 system+user 拼接，且 system 文案里也含同名段头——
        // 先定位 user 段再取命中清单，只数真实命中行
        val reviewPrompt = llm.prompts[1]
            .substringAfter("\n=====USER=====\n")
            .substringAfter("【关键词命中的已有记忆】")
        val hitLines = reviewPrompt.lines().count { it.startsWith("- ") }
        assertTrue(hitLines in 1..30, "命中回喂截断到最新 30 条（实际 $hitLines）：复核 prompt 不被撑爆")
    }

    // ------------------------------------------------------------------
    // 异步整理（不阻塞主循环下一步）
    // ------------------------------------------------------------------

    @Test
    fun `reflectAsync returns before task runs and completes later`() {
        val s = service()
        val queued = mutableListOf<Runnable>()
        val llm = FakeLLM(mutableListOf("""{"memories":[{"kind":"fact","content":"异步记的事"}]}"""))
        val reflector = MemoryReflector(
            memoryAgent = MemoryAgent(llm),
            memoryService = s,
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist-async", ".json").absolutePath)
            ),
            actionLog = ActionLog(),
            executor = java.util.concurrent.Executor { queued.add(it) }
        )

        reflector.reflectAsync("private:30")

        assertTrue(s.getMemoriesOf(MemorySubject.user(30L)).isEmpty(),
            "提交后立即返回，任务还没执行——退出路径不被 LLM 阻塞")
        assertEquals(1, queued.size, "整理任务已入队")

        queued.forEach { it.run() }
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(30L)).size, "任务执行后写入")
        assertTrue(reflector.awaitPending(100), "任务完成后 awaitPending 立即放行")
    }

    @Test
    fun `awaitPending blocks until in-flight reflection finishes`() {
        val s = service()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val llm = FakeLLM(mutableListOf("""{"memories":[{"kind":"fact","content":"停机前的事"}]}"""))
        val reflector = MemoryReflector(
            memoryAgent = MemoryAgent(llm),
            memoryService = s,
            chatHistoryService = ChatHistoryService(
                JsonFileChatHistoryRepository(File.createTempFile("hist-wait", ".json").absolutePath)
            ),
            actionLog = ActionLog(),
            executor = java.util.concurrent.Executor { task ->
                Thread {
                    started.countDown()
                    release.await()  // 模拟 LLM 还在跑
                    task.run()
                }.apply { isDaemon = true }.start()
            }
        )

        reflector.reflectAsync("private:31")
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS), "执行线程已启动")

        assertFalse(reflector.awaitPending(50), "任务执行中应等不到，超时返回 false")
        release.countDown()
        assertTrue(reflector.awaitPending(2_000), "任务完成后放行")
        assertEquals(1, s.getMemoriesOf(MemorySubject.user(31L)).size, "停机排干后记忆已写入")
    }
}

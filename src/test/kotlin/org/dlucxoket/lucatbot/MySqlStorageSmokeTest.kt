package org.dlucxoket.lucatbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord
import org.dlucxoket.lucatbot.bot.memory.MemoryItem
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlChatHistoryRepository
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlConfig
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlMemoryRepository
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlStorage
import org.dlucxoket.lucatbot.bot.storage.mysql.MySqlTodoRepository
import org.dlucxoket.lucatbot.bot.todo.TodoItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * MySQL 存储冒烟测试（真实连接 lucatbot_test 库；MySQL 不可用时**跳过**而非失败）
 *
 * 覆盖：建库建表幂等、三个仓库的读写往返、limit/before 查询语义、
 * 枚举与列表字段（subjectType/tags/messageId）落库往返、JSON 存量迁移。
 */
class MySqlStorageSmokeTest {

    private val testConfig = MySqlConfig(
        host = "127.0.0.1", port = 3306, database = "LucatBot_test",
        username = "root", password = "123456",
        importFromJson = false  // 同步单独用例验证，往返测试不导入存量
    )

    /** 初始化测试库（连接瞬时故障重试 2 次）；连不上 → assumeTrue 失败 → 测试跳过 */
    private fun initOrSkip(): MySqlStorage {
        val storage = MySqlStorage(testConfig)
        var last: Throwable? = null
        repeat(3) { attempt ->
            val result = runCatching { storage.init() }
            if (result.isSuccess) return storage
            last = result.exceptionOrNull()
            if (attempt < 2) Thread.sleep(500)
        }
        assumeTrue(false, "MySQL 不可用，跳过冒烟测试：${last?.message}")
        return storage
    }

    /**
     * 连接类瞬时故障（本机 socket 资源抖动，连续多轮测试后偶发）→ 跳过而非失败；
     * **只识别连接故障**——SQL 语义错误照常抛出失败，不许被跳过掩盖。
     */
    private fun <T> withDbOrSkip(block: () -> T): T {
        try {
            return block()
        } catch (e: java.sql.SQLException) {
            val msg = e.message.orEmpty()
            val transient = msg.contains("CommunicationsException", ignoreCase = true) ||
                msg.contains("client portion of a socket", ignoreCase = true)
            assumeTrue(false, if (transient) "MySQL 连接瞬时故障，跳过：$msg" else throw e)
            throw e  // unreachable
        }
    }

    /** 清空测试库三张表（保证用例可重复执行） */
    private fun clean(storage: MySqlStorage) {
        storage.update("DELETE FROM chat_history")
        storage.update("DELETE FROM memories")
        storage.update("DELETE FROM todos")
    }

    @Test
    fun `chat history roundtrip with limit and before`() = withDbOrSkip {
        val storage = initOrSkip()
        clean(storage)
        val repo = MySqlChatHistoryRepository(storage)

        val conv = "private:999001"
        listOf(
            ChatMessageRecord(id = "m1", conversationId = conv, senderId = 1L, senderName = "甲",
                senderNickname = "甲QQ名",
                imageUrls = listOf("https://x/a.png", "https://x/b.png"),
                memorized = true,
                content = "第一句", timestamp = 1000L, messageId = 11L),
            ChatMessageRecord(id = "m2", conversationId = conv, senderId = 2L, senderName = "乙",
                content = "第二句", timestamp = 2123L),   // 非整秒：验证 DATETIME(3) 毫秒不丢
            ChatMessageRecord(id = "m3", conversationId = conv, senderId = 1L, senderName = "甲",
                content = "第三句", timestamp = 3000L, isFromBot = true)
        ).forEach { repo.save(it) }

        // 全量按时间升序 + 毫秒级时间戳往返无损
        val all = repo.findByConversation(conv, 0)
        assertEquals(listOf("m1", "m2", "m3"), all.map { it.id })
        assertEquals(listOf(1000L, 2123L, 3000L), all.map { it.timestamp }, "DATETIME(3) 往返保毫秒")

        // limit=2 → 最近 2 条（JSON 实现 takeLast 语义）
        assertEquals(listOf("m2", "m3"), repo.findByConversation(conv, 2).map { it.id })

        // before → 只取更早
        assertEquals(listOf("m1"), repo.findByConversation(conv, 0, before = 2000L).map { it.id })

        // 字段往返
        val first = all[0]
        assertEquals(1L, first.senderId)
        assertEquals("甲", first.senderName)
        assertEquals("甲QQ名", first.senderNickname, "sender_nickname 列往返（群名片 vs QQ昵称分辨）")
        assertNull(all[1].senderNickname, "无名片记录 → NULL 往返仍为 null")
        assertEquals(
            listOf("https://x/a.png", "https://x/b.png"), first.imageUrls,
            "image_urls 列（JSON 存 TEXT）往返"
        )
        assertNull(all[1].imageUrls, "无图记录 → NULL 往返仍为 null")
        assertTrue(first.memorized, "memorized 列往返（true）")
        assertFalse(all[1].memorized, "memorized 列默认 false")
        assertEquals(11L, first.messageId)
        assertNull(all[1].messageId)
        assertTrue(first.isFromBot == false && all[2].isFromBot)

        // upsert：同 id 覆盖
        repo.save(all[0].copy(content = "改过了"))
        assertEquals(3, repo.findByConversation(conv, 0).size)
        assertEquals("改过了", repo.findByConversation(conv, 0).first().content)

        // findAll / delete——启动清理（collapseStoredTwins）依赖的两个接口操作
        assertEquals(3, repo.findAll().count { it.conversationId == conv }, "findAll 按会话计数")
        repo.delete("m3")
        assertEquals(2, repo.findByConversation(conv, 0).size, "delete 移除指定行")
        repo.delete("m3")  // 不存在 → 无操作
        assertEquals(2, repo.findByConversation(conv, 0).size, "重复删除是无操作")
        clean(storage)
    }

    @Test
    fun `memory roundtrip preserves subject type and tags`() = withDbOrSkip {
        val storage = initOrSkip()
        clean(storage)
        val repo = MySqlMemoryRepository(storage)

        repo.save(MemoryItem(
            id = "mem-user", userId = 123L, subjectType = MemorySubject.Type.USER,
            type = MemoryItem.MemoryType.FACT, content = "个人的事实",
            tags = listOf("名字", "喜好"), relatedUserIds = listOf(9L, 8L), strength = 0.7
        ))
        repo.saveAll(listOf(
            MemoryItem(id = "mem-group", userId = 123L, subjectType = MemorySubject.Type.GROUP,
                type = MemoryItem.MemoryType.OPINION, content = "群聊的看法")
        ))

        // 同号不同主体键空间隔离（服务层过滤的前提：字段如实落库）
        val userItems = repo.findByUserId(123L).filter { it.subjectType == MemorySubject.Type.USER }
        val groupItems = repo.findByUserId(123L).filter { it.subjectType == MemorySubject.Type.GROUP }
        assertEquals(1, userItems.size)
        assertEquals(1, groupItems.size)

        val loaded = repo.findById("mem-user")!!
        // 关键词是集合语义（检索/交集不看顺序）——关联表不存顺序，按集合断言
        assertEquals(setOf("名字", "喜好"), loaded.tags.toSet(), "tags 关联往返")
        assertEquals(listOf(9L, 8L), loaded.relatedUserIds, "relatedUserIds JSON 往返")
        assertEquals(0.7, loaded.strength, 1e-9)
        assertEquals(MemoryItem.MemoryType.FACT, loaded.type)

        // findByTags 与 JSON 实现同语义（交集）——走 tags/memory_tags 索引 JOIN
        assertEquals(1, repo.findByTags(listOf("喜好")).size)
        assertEquals(0, repo.findByTags(listOf("不存在")).size)

        // 关键词规范化落地：关联表有行、注册表去重（"名字"只存一份）
        val junctionRows = storage.queryOne(
            "SELECT COUNT(*) AS c FROM memory_tags", map = { it.getLong("c") }
        ) ?: 0L
        assertTrue(junctionRows > 0, "memory_tags 关联表应有行")
        val nameTags = storage.queryOne(
            "SELECT COUNT(*) AS c FROM tags WHERE name = '名字'", map = { it.getLong("c") }
        ) ?: 0L
        assertEquals(1L, nameTags, "tags 注册表按词唯一")

        repo.delete("mem-user")
        assertNull(repo.findById("mem-user"), "删除记忆后关联应随 FK 级联清理")
        assertEquals(0L, storage.queryOne(
            "SELECT COUNT(*) AS c FROM memory_tags WHERE memory_id = 'mem-user'",
            map = { it.getLong("c") }
        ), "FK 级联：删记忆自动清关联")
        clean(storage)
    }

    @Test
    fun `todo roundtrip`() = withDbOrSkip {
        val storage = initOrSkip()
        clean(storage)
        val repo = MySqlTodoRepository(storage)

        repo.save(TodoItem(id = "t1", content = "帮你看群", conversationId = "group:5"))
        repo.save(TodoItem(id = "t2", content = "取快递", done = true, conversationId = null))

        val all = repo.findAll()
        assertEquals(2, all.size)
        val t1 = all.first { it.id == "t1" }
        assertEquals("帮你看群", t1.content)
        assertEquals("group:5", t1.conversationId)
        assertFalse(t1.done)
        assertTrue(all.first { it.id == "t2" }.done)
        assertNull(all.first { it.id == "t2" }.conversationId, "NULL 往返不能变空串")
        clean(storage)
    }

    @Test
    fun `json sync runs every startup and never overwrites existing rows`() = withDbOrSkip {
        // 注意：data 目录的 json 可能正被运行中的实例持续写入——
        // 所有断言基于「导入前的 id 快照」，不对易变的总行数做相等断言
        val snapshotIds = runCatching {
            jacksonObjectMapper().readValue<List<ChatMessageRecord>>(java.io.File("data/chat-history.json"))
                .map { it.id }.toSet()
        }.getOrElse { e ->
            assumeTrue(false, "json 解析失败（可能正被写入）: ${e.message}")
            emptySet()
        }
        assertTrue(snapshotIds.isNotEmpty(), "json 应有存量行")

        // 清空后第一次启动：快照里的行全部补进来（补缺失）
        val cleaner = initOrSkip()
        cleaner.update("DELETE FROM chat_history")

        val syncCfg = testConfig.copy(importFromJson = true)
        val s1 = MySqlStorage(syncCfg)
        val init1 = runCatching { s1.init() }
        assumeTrue(init1.isSuccess, "MySQL 不可用或同步失败: ${init1.exceptionOrNull()?.message}")
        assertTrue(
            snapshotIds.all { it in idsOf(s1) },
            "首启应补齐 json 快照中的全部行（缺失 ${snapshotIds.size - idsOf(s1).size}）"
        )

        // 模拟「库里已更新、json 还是旧的」：改掉一行内容
        val repo1 = MySqlChatHistoryRepository(s1)
        val conv = s1.queryOne(
            "SELECT conversation_id AS c FROM chat_history LIMIT 1",
            map = { it.getString("c") }
        )!!
        val victim = repo1.findByConversation(conv, 1).first()
        repo1.save(victim.copy(content = "库里的更新（不能被旧 json 改回）"))

        // 第二次启动同步：已存在的行不被覆盖；快照行仍全部在库
        val s2 = MySqlStorage(syncCfg)
        val init2 = runCatching { s2.init() }
        assumeTrue(init2.isSuccess, "MySQL 不可用或同步失败: ${init2.exceptionOrNull()?.message}")
        val after = MySqlChatHistoryRepository(s2)
            .findByConversation(conv, 50).first { it.id == victim.id }
        assertEquals(
            "库里的更新（不能被旧 json 改回）", after.content,
            "json 同步只补缺失行，不能覆盖库里已更新的行"
        )
        assertTrue(
            snapshotIds.all { it in idsOf(s2) },
            "二次同步后快照行仍全部在库（缺失才插的幂等语义）"
        )
    }

    private fun idsOf(storage: MySqlStorage): Set<String> =
        storage.query("SELECT id FROM chat_history", map = { it.getString("id") }).toSet()

    @Test
    fun `time columns are datetime with millisecond precision`() = withDbOrSkip {
        val storage = initOrSkip()
        // 旧 BIGINT 结构已在本次 init 中迁移；断言列类型 = datetime(3)
        listOf(
            "chat_history" to "sent_at",
            "memories" to "created_at",
            "todos" to "created_at"
        ).forEach { (table, column) ->
            val type = storage.queryOne(
                "SELECT CONCAT(DATA_TYPE, '(', DATETIME_PRECISION, ')') AS tp " +
                    "FROM information_schema.COLUMNS " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                testConfig.database, table, column,
                map = { it.getString("tp") }
            )
            assertEquals("datetime(3)", type, "$table.$column 应为 DATETIME(3)")
        }
    }

    @Test
    fun `ensureTagId burns no auto increment for existing words`() {
        // 块体（JVM void）：JUnit 对 @Test 的返回类型敏感，表达式体若推断出
        // 非 Unit（本用例末句 update 返回 Int）会被静默不予发现
        withDbOrSkip {
        // 跳空根因回归：旧实现每次写记忆都对老词条 INSERT IGNORE 撞唯一键，
        // MySQL 为跳过的行照样消耗自增 ID → tags.id 持续跳空
        val storage = initOrSkip()
        val word = "zstest-${System.nanoTime()}"
        storage.connection().use { conn ->
            conn.autoCommit = true
            val first = storage.ensureTagId(conn, word)
            val maxAfter1 = storage.queryOne(
                "SELECT MAX(id) AS m FROM tags", map = { it.getInt("m") }
            )
            repeat(10) {
                assertEquals(first, storage.ensureTagId(conn, word), "已存在词返回同一 id")
            }
            val maxAfter10 = storage.queryOne(
                "SELECT MAX(id) AS m FROM tags", map = { it.getInt("m") }
            )
            assertEquals(maxAfter1, maxAfter10,
                "已存在词条反复 ensure 不得再消耗自增 ID（跳空根因已修）")
            // 新词恰好前进 1（先查后插：只在真正新词时 INSERT）
            val word2 = "$word-b"
            storage.ensureTagId(conn, word2)
            val maxAfterNew = storage.queryOne(
                "SELECT MAX(id) AS m FROM tags", map = { it.getInt("m") }
            )
            assertEquals((maxAfter10 ?: error("MAX(id) 应非空")) + 1, maxAfterNew, "新词条插入恰好 +1")
            // 清理测试词条（未进关联表，可直接删），保证用例可重复执行
            storage.update(conn, "DELETE FROM tags WHERE name IN (?, ?)", word, word2)
        }
        }
    }

    @Test
    fun `chat history has sender nickname column for card vs qq name`() = withDbOrSkip {
        // 老库（建表时无该列）靠启动期 ensureColumn 补列——测试库首跑即走该路径
        val storage = initOrSkip()
        val type = storage.queryOne(
            "SELECT DATA_TYPE AS tp FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
            testConfig.database, "chat_history", "sender_nickname",
            map = { it.getString("tp") }
        )
        assertEquals("varchar", type, "chat_history.sender_nickname 列应存在（老库自动补列）")
    }

    @Test
    fun `chat history has image urls column`() = withDbOrSkip {
        // 图片通道的地址列（同为启动期 ensureColumn 补列路径）
        val storage = initOrSkip()
        val type = storage.queryOne(
            "SELECT DATA_TYPE AS tp FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
            testConfig.database, "chat_history", "image_urls",
            map = { it.getString("tp") }
        )
        assertEquals("text", type, "chat_history.image_urls 列应存在且为 TEXT（老库自动补列）")
    }

    @Test
    fun `chat history has memorized column`() = withDbOrSkip {
        // 「已记忆」标记列（同为启动期 ensureColumn 补列路径）
        val storage = initOrSkip()
        val type = storage.queryOne(
            "SELECT DATA_TYPE AS tp FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?",
            testConfig.database, "chat_history", "memorized",
            map = { it.getString("tp") }
        )
        // MySQL 的 BOOLEAN 是 TINYINT(1) 别名 → DATA_TYPE=tinyint
        assertEquals("tinyint", type, "chat_history.memorized 列应存在（老库自动补列）")
    }
}

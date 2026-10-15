package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord
import org.dlucxoket.lucatbot.bot.storage.mysql.planChatImport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * json 导入的孪生感知筛选（[planChatImport]）
 *
 * 回归的线上故障：启动清理删掉「与库内运行时行孪生」的迁移源 json 行，
 * 下次启动「补缺失」导入又把它们原样灌回来 → 「import 补 → 清理删」
 * 互为逆操作，每次启动固定打「合并孪生双份 31 行」且永不收敛。
 * （孪生幸存方是库内运行时行、json 里只有孤行，迁移源自身折叠数恒为 0，救不了。）
 */
class ChatHistoryImportPlanTest {

    private fun row(
        id: String,
        conv: String = "group:1",
        sender: Long = 5L,
        content: String = "你好",
        ts: Long = 5_000L,
        fromBot: Boolean = false
    ) = ChatMessageRecord(
        id = id, conversationId = conv, senderId = sender,
        content = content, timestamp = ts, isFromBot = fromBot
    )

    @Test
    fun `skips json row when its twin already exists in db`() {
        // 线上形态：json 行（CQ 原文）与库内爬楼行（同一物理消息）归一化后同内容
        val json = row(id = "hist:group:1:5:5000:-1201158052", content = "[CQ:at,qq=1]你好")
        val db = row(id = "hist:group:1:5:5000:12345", content = "你好")

        val plan = planChatImport(listOf(json), listOf(db))

        assertEquals(0, plan.toInsert.size, "孪生已入库的 json 行不得回灌（否则与启动清理拉锯）")
        assertEquals(0, plan.existing)
        assertEquals(1, plan.twinSkipped)
    }

    @Test
    fun `skips json row whose twin is an authoritative msg row`() {
        val json = row(id = "uuid-json", ts = 5_000L)
        val db = row(id = "msg:group:1:88", ts = 5_100L)

        val plan = planChatImport(listOf(json), listOf(db))

        assertEquals(0, plan.toInsert.size, "uuid × msg: 孪生 = 同一物理消息，不回灌")
        assertEquals(1, plan.twinSkipped)
    }

    @Test
    fun `inserts json row when db has no twin for it`() {
        val json = row(id = "hist:group:1:5:5000:1")
        val db = row(id = "msg:group:1:88", ts = 65_000L) // 同会话但相距远超 10s

        val plan = planChatImport(listOf(json), listOf(db))

        assertEquals(listOf(json), plan.toInsert, "库里没有同一物理消息 → 照常补缺失")
        assertEquals(0, plan.twinSkipped)
        assertEquals(0, plan.existing)
    }

    @Test
    fun `counts id hits as existing without twin check`() {
        val json = row(id = "msg:group:1:88")
        val db = row(id = "msg:group:1:88")

        val plan = planChatImport(listOf(json), listOf(db))

        assertEquals(0, plan.toInsert.size)
        assertEquals(1, plan.existing, "按 id 已存在 → 计入已存在（不覆盖语义）")
        assertEquals(0, plan.twinSkipped)
    }

    @Test
    fun `keeps two uuid rows as real repeats not twins`() {
        // 真实连发保护在导入侧同样成立：两个无前缀 UUID 行不折叠 → 后一条照常补
        val json = row(id = "uuid-a", ts = 5_000L, content = "哈哈")
        val db = row(id = "uuid-b", ts = 5_500L, content = "哈哈")

        val plan = planChatImport(listOf(json), listOf(db))

        assertEquals(listOf(json), plan.toInsert, "两个 UUID ≠ 孪生（真实连发），不能被当成重复跳过")
        assertEquals(0, plan.twinSkipped)
    }
}

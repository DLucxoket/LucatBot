package org.dlucxoket.lucatbot

import com.mikuac.shiro.core.Bot
import com.mikuac.shiro.dto.action.common.ActionRaw
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.request.PendingRequestStore
import org.dlucxoket.lucatbot.bot.tool.FriendRequestInput
import org.dlucxoket.lucatbot.bot.tool.FriendRequestTool
import org.dlucxoket.lucatbot.bot.tool.GroupRequestInput
import org.dlucxoket.lucatbot.bot.tool.GroupRequestTool
import org.dlucxoket.lucatbot.bot.tool.ToolServices
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito
import java.io.File

/**
 * 群邀请/加群申请/好友请求 审批链路：事件入队 → list 句柄 → approve/reject 换 flag 调 API
 *
 * 背景（行为变更）：旧版群请求事件被静默忽略、好友请求硬编码自动同意；
 * 现统一入 [PendingRequestStore]，由 LLM 经 Tool 决策（不加超时兜底——
 * flag 过期如实报错、LLM 放弃，交由对方重发）。
 *
 * Mockito 说明：匹配字符串参数一律用 [anyT]（= `any()`，**含 null**）——
 * `anyString()` 不匹配 null，而 reason/remark 可为 null，stub 会落空返回
 * null 再 NPE；Bot 是 Java 类（平台类型参数），null 传参不会被 Kotlin 检查拦截。
 */
class PendingRequestToolsTest {

    /** Mockito.any 的泛型包装（匹配 null；Java 平台类型参数可接收） */
    private fun <T> anyT(): T = Mockito.any()

    /** 独立队列 + 绑到两个 Tool（各测换新队列，互不串） */
    private fun newStore(): PendingRequestStore {
        val store = PendingRequestStore()
        val services = ToolServices(
            memoryService = MemoryService(JsonFileMemoryRepository(File.createTempFile("mem-req", ".json").absolutePath)),
            chatHistoryService = ChatHistoryService(JsonFileChatHistoryRepository(File.createTempFile("hist-req", ".json").absolutePath)),
            lucatBot = LucatBot(),
            pendingRequestStore = store
        )
        GroupRequestTool.bindServices(services)
        FriendRequestTool.bindServices(services)
        return store
    }

    private fun mockBot(): Bot = Mockito.mock(Bot::class.java)

    private fun okRaw(): ActionRaw = ActionRaw().apply { status = "ok" }

    // ---------- PendingRequestStore ----------

    @Test
    fun `store assigns monotonic seq and filters by kind`() {
        val store = PendingRequestStore()
        val inv = store.add(PendingRequestStore.Kind.GROUP_INVITE, "flag-a", subType = "invite", groupId = 1L, userId = 2L, invitorId = 3L)
        val fri = store.add(PendingRequestStore.Kind.FRIEND, "flag-b", userId = 4L, comment = "我是XX")

        assertEquals(1, inv.seq, "seq 从 1 起单调分配")
        assertEquals(2, fri.seq)
        assertEquals(listOf(inv), store.list(PendingRequestStore.Kind.GROUP_INVITE), "按类别隔离")
        assertEquals(2, store.listAll().size, "listAll 全量")

        // find：seq 句柄（带不带 # 都认）→ 群号/QQ 号宽松匹配
        assertEquals(inv, store.find(PendingRequestStore.Kind.GROUP_INVITE, "1"))
        assertEquals(inv, store.find(PendingRequestStore.Kind.GROUP_INVITE, "#1"))
        assertEquals(fri, store.find(PendingRequestStore.Kind.FRIEND, "4"), "按 QQ 号找")
        assertNull(store.find(PendingRequestStore.Kind.FRIEND, "1"), "跨类别不串")

        store.remove(fri)
        assertEquals(0, store.list(PendingRequestStore.Kind.FRIEND).size, "处理完出队")
        store.remove(fri) // 幂等：重复移除不抛
        assertEquals(1, store.size())
    }

    // ---------- GroupRequestTool ----------

    @Test
    fun `group list shows seq handle and empty queue says so`() {
        val store = newStore()
        val empty = GroupRequestTool.processObject(GroupRequestInput())
        assertTrue(empty.success && empty.note!!.contains("没有待处理"), "空队列提示：${empty.note}")

        store.add(PendingRequestStore.Kind.GROUP_INVITE, "f1", subType = "invite", groupId = 811788212L, userId = 3981541235L, invitorId = 271524789L, comment = "来玩")
        store.add(PendingRequestStore.Kind.GROUP_JOIN, "f2", subType = "add", groupId = 811788212L, userId = 12345L, comment = "同学推荐")

        val listed = GroupRequestTool.processObject(GroupRequestInput(action = "list"))
        assertTrue(listed.success)
        assertTrue(listed.note!!.contains("#1") && listed.note!!.contains("入群邀请"), "句柄+类别：${listed.note}")
        assertTrue(listed.note!!.contains("已等待"), "等待时长助判断紧迫性：${listed.note}")
        assertTrue(listed.note!!.contains("加群申请") && listed.note!!.contains("同学推荐"), "两类同列：${listed.note}")
    }

    @Test
    fun `group approve resolves seq to flag and passes original subType`() {
        val bot = mockBot()
        Mockito.`when`(bot.setGroupAddRequest(anyT(), anyT(), anyBoolean(), anyT())).thenReturn(okRaw())
        GroupRequestTool.bindBot(bot)

        val store = newStore()
        store.add(PendingRequestStore.Kind.GROUP_INVITE, "flag-99", subType = "invite", groupId = 777L, userId = 888L)

        val ok = GroupRequestTool.processObject(GroupRequestInput(action = "approve", id = "#1"))
        assertTrue(ok.success && ok.note!!.contains("已同意"), "结果说明：${ok.note}")
        // subType 必须原样回传（invite 不能被改成 add）；reason=null 原样传
        Mockito.verify(bot).setGroupAddRequest("flag-99", "invite", true, null)
        assertEquals(0, store.size(), "同意后出队")

        val unknown = GroupRequestTool.processObject(GroupRequestInput(action = "approve", id = "42"))
        assertFalse(unknown.success, "句柄不存在必须失败")
        assertTrue(unknown.error!!.contains("重新 list"), "自愈报错引导：${unknown.error}")

        val noId = GroupRequestTool.processObject(GroupRequestInput(action = "reject"))
        assertFalse(noId.success, "缺 id 必须失败")
        assertTrue(noId.error!!.contains("list"), "报错给出正确写法：${noId.error}")
    }

    @Test
    fun `group reject passes reason and failure keeps request queued`() {
        val store = newStore()
        store.add(PendingRequestStore.Kind.GROUP_JOIN, "flag-x", subType = "add", groupId = 5L, userId = 6L)
        val bot = mockBot()
        // flag 过期失败：status=failed + retCode
        val fail = ActionRaw().apply { status = "failed"; retCode = 100 }
        Mockito.`when`(bot.setGroupAddRequest(anyT(), anyT(), anyBoolean(), anyT())).thenReturn(fail)
        GroupRequestTool.bindBot(bot)

        val rejected = GroupRequestTool.processObject(GroupRequestInput(action = "reject", id = "1", reason = "不熟"))
        assertFalse(rejected.success, "API 失败如实回传")
        assertTrue(rejected.error!!.contains("retCode=100"), "带 retCode：${rejected.error}")
        Mockito.verify(bot).setGroupAddRequest("flag-x", "add", false, "不熟")
        assertEquals(1, store.size(), "失败不许出队（否则重试机会丢失）")

        // 成功路径出队
        Mockito.`when`(bot.setGroupAddRequest(anyT(), anyT(), anyBoolean(), anyT())).thenReturn(okRaw())
        val retry = GroupRequestTool.processObject(GroupRequestInput(action = "reject", id = "1", reason = "不熟"))
        assertTrue(retry.success, "重试成功：${retry.error}")
        assertEquals(0, store.size(), "成功才出队")
    }

    @Test
    fun `group unknown action gives write-up example`() {
        newStore()
        val bad = GroupRequestTool.processObject(GroupRequestInput(action = "handle"))
        assertFalse(bad.success)
        assertTrue(bad.error!!.contains("{\"action\":\"list\"}"), "报错带正确写法：${bad.error}")
    }

    // ---------- FriendRequestTool ----------

    @Test
    fun `friend list shows verification message`() {
        val store = newStore()
        val empty = FriendRequestTool.processObject(FriendRequestInput())
        assertTrue(empty.success && empty.note!!.contains("没有待处理"), "空队列：${empty.note}")

        store.add(PendingRequestStore.Kind.FRIEND, "ff", userId = 4242L, comment = "我是群里老王")
        val listed = FriendRequestTool.processObject(FriendRequestInput(action = "list"))
        assertTrue(listed.note!!.contains("#1") && listed.note!!.contains("4242"), "句柄+QQ：${listed.note}")
        assertTrue(listed.note!!.contains("我是群里老王"), "验证消息是加人依据：${listed.note}")
    }

    @Test
    fun `friend approve passes remark reject passes reason`() {
        val store = newStore()
        store.add(PendingRequestStore.Kind.FRIEND, "flag-f", userId = 99L, comment = "hi")
        store.add(PendingRequestStore.Kind.FRIEND, "flag-g", userId = 100L)
        val bot = mockBot()
        Mockito.`when`(bot.setFriendAddRequest(anyT(), anyBoolean(), anyT())).thenReturn(okRaw())
        FriendRequestTool.bindBot(bot)

        val approved = FriendRequestTool.processObject(FriendRequestInput(action = "approve", id = "1", remark = "老王"))
        assertTrue(approved.success && approved.note!!.contains("已同意"), "${approved.note}")
        Mockito.verify(bot).setFriendAddRequest("flag-f", true, "老王")

        val rejected = FriendRequestTool.processObject(FriendRequestInput(action = "reject", id = "2", reason = "不加陌生人"))
        assertTrue(rejected.success)
        Mockito.verify(bot).setFriendAddRequest("flag-g", false, "不加陌生人")
        assertEquals(0, store.size(), "两条都处理完")
    }

    @Test
    fun `friend tool empty input satisfies error contract`() {
        newStore()
        // ToolErrorContractTest 对全量 Tool 跑 {}——默认 list 走无害路径；
        // 断言必须解析后看 error 是否**非 null**（结果恒带 "error":null 键，子串判断会误报）
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val map = mapper.readValue(FriendRequestTool.process("{}"), Map::class.java)
        val success = map["success"]
        val error = map["error"]
        assertFalse(success == true && error != null, "契约：error 非空不得配 success=true")
        assertNull(error, "空输入默认 list 不应报错：$map")
    }
}

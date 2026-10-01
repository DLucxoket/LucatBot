package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.annotation.JsonAlias
import org.dlucxoket.lucatbot.bot.request.PendingRequestStore

/**
 * 好友请求入参（list/approve/reject 三动作；宽容别名同 [GroupRequestInput]）
 *
 * @param action 默认 list（空输入走无害路径，满足错误契约测试）
 * @param id 处理句柄（list 返回的 seq；也接受对方 QQ 号）
 * @param reason 拒绝理由（可选）
 * @param remark 同意时的备注名（可选）
 */
data class FriendRequestInput(
    @JsonAlias("op", "do", "operation")
    val action: String = "list",
    @JsonAlias("seq", "target", "qq", "userId", "user_id", "request")
    val id: String = "",
    val reason: String? = null,
    val remark: String? = null
)

/** 好友请求处理结果（[FriendRequestResult.note] 语义同 [GroupRequestResult.note]） */
data class FriendRequestResult(
    val success: Boolean = true,
    val note: String? = null,
    val error: String? = null
)

/**
 * 好友请求审批工具——list + approve + reject
 *
 * 行为变更（相对旧版）：此前 [com.mikuac.shiro.core.Bot] 收到好友请求
 * **无条件自动同意**（MainPlugin 硬编码），机器人对「谁加好友」零感知；
 * 现改为入 [PendingRequestStore] 队列 + 记操作日志，由 LLM 看验证消息后
 * 决定同意/拒绝——更像真人（陌生人请求可拒）。
 *
 * 有意不做超时自动兜底：OneBot flag 有时效，但请求放置过久本身即「当时
 * 没在看」，过期时 API 报错、工具如实回传、LLM 放弃——交由对方重发。
 */
object FriendRequestTool : Tool<FriendRequestInput, FriendRequestResult>() {
    override val name: String = "FriendRequestTool"

    override val description: String =
        "功能：处理好友请求（action=list 列出待处理；approve 同意（可带 remark 备注）；reject 拒绝（可带 reason 理由），需带 id）。\n" +
            "何时使用：操作日志出现「收到好友请求」后，先 list 看验证消息与等待时长，再决定加不加。"

    override val parameterHint: String =
        "JSON: {\"action\": \"list|approve|reject\", \"id\": \"list 返回的序号\", \"reason\": \"拒绝理由（可选）\", \"remark\": \"同意时的备注（可选）\"}"

    override val inputClass: Class<FriendRequestInput> = FriendRequestInput::class.java

    /** 请求队列（启动时经 [bindServices] 注入 Spring 单例） */
    private var store: PendingRequestStore? = null

    override fun bindServices(services: ToolServices) {
        store = services.pendingRequestStore
    }

    override fun handle(input: FriendRequestInput): FriendRequestResult {
        val store = this.store ?: return FriendRequestResult(
            success = false, error = "请求队列未绑定（bindServices 未调用）"
        )
        return when (input.action.trim().lowercase()) {
            "list", "" -> listResult(store)

            "approve", "reject" -> {
                val accept = input.action.trim().lowercase() == "approve"
                // 校验先于取 Bot（同 GroupRequestTool：无句柄时的自愈报错不依赖连接）
                if (input.id.isBlank()) {
                    return FriendRequestResult(
                        success = false,
                        error = "缺少 id——先用 {\"action\":\"list\"} 看待处理列表，再拿其中的序号操作"
                    )
                }
                val pending = store.find(PendingRequestStore.Kind.FRIEND, input.id)
                    ?: return FriendRequestResult(
                        success = false,
                        error = "没有编号「${input.id}」的待处理好友请求——可能已被处理，重新 list 确认"
                    )
                runTool(onError = { FriendRequestResult(success = false, error = it) }) { bot ->
                    // 第三参：同意=备注名，拒绝=理由（OneBot set_friend_add_request 语义）
                    val third = if (accept) input.remark else input.reason
                    val resp = bot.setFriendAddRequest(pending.flag, accept, third)
                    if (resp.status != "ok") {
                        return@runTool FriendRequestResult(
                            success = false,
                            error = "处理失败：status=${resp.status} retCode=${resp.retCode}（flag 可能已过期，请求放置过久需对方重新发起）"
                        )
                    }
                    store.remove(pending)
                    val verb = if (accept) "同意" else "拒绝"
                    FriendRequestResult(note = "已$verb 好友请求：${pending.userId}（${pending.comment ?: "无验证消息"}）")
                }
            }

            else -> FriendRequestResult(
                success = false,
                error = "action 必须是 list / approve / reject——正确写法：{\"action\":\"list\"}"
            )
        }
    }

    /** list：序号 + 对方 + 验证消息 + 等待时长（LLM 判断加不加的依据） */
    private fun listResult(store: PendingRequestStore): FriendRequestResult {
        val all = store.list(PendingRequestStore.Kind.FRIEND)
        if (all.isEmpty()) {
            return FriendRequestResult(note = "（当前没有待处理的好友请求）")
        }
        val text = all.joinToString("\n") { r ->
            val waited = (System.currentTimeMillis() - r.arrivedAt) / 60_000
            val comment = r.comment?.takeIf { it.isNotBlank() }?.let { "，验证消息「$it」" } ?: "，无验证消息"
            "#${r.seq} 好友请求：QQ ${r.userId}$comment，已等待 ${waited} 分钟"
        }
        return FriendRequestResult(note = text)
    }
}

package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.annotation.JsonAlias
import org.dlucxoket.lucatbot.bot.request.PendingRequestStore

/**
 * 群请求入参（list/approve/reject 三动作）
 *
 * 宽容解析（@JsonAlias 收常见别名）：模型常把 id 写进 seq/target/groupId。
 *
 * @param action list=列出待处理；approve=同意；reject=拒绝（默认 list——
 *   空输入走无害路径，错误契约测试对全量 Tool 跑 `{}`）
 * @param id 处理句柄：approve/reject 必填，填 list 返回的 seq（也接受群号）
 * @param reason 拒绝理由（可选，回传给 OneBot）
 */
data class GroupRequestInput(
    @JsonAlias("op", "do", "operation")
    val action: String = "list",
    @JsonAlias("seq", "target", "groupId", "group_id", "request")
    val id: String = "",
    val reason: String? = null
)

/**
 * 群请求处理结果
 *
 * @param note list 时为待处理清单文本（LLM 直接读）；approve/reject 时为结果说明
 */
data class GroupRequestResult(
    val success: Boolean = true,
    val note: String? = null,
    val error: String? = null
)

/**
 * 群请求审批工具——入群邀请 / 加群申请的 list + approve + reject
 *
 * 事件链路：Shiro [com.mikuac.shiro.dto.event.request.GroupAddRequestEvent]
 * 到达 → [PendingRequestStore] 入队 + 记操作日志 → 本工具供 LLM 查看与处理。
 * 此前这类事件被静默忽略（既不入档也不提示），机器人被邀请进群毫无感知。
 *
 * 处理句柄用 store 分配的 seq（list 里可见），approve/reject 内部换回
 * OneBot flag——LLM 不接触不透明的 flag 字符串。
 */
object GroupRequestTool : Tool<GroupRequestInput, GroupRequestResult>() {
    override val name: String = "GroupRequestTool"

    override val description: String =
        "功能：处理入群邀请与加群申请（action=list 列出待处理；approve 同意；reject 拒绝，需带 id 与可选 reason）。\n" +
            "何时使用：操作日志出现「收到入群邀请/加群申请」后，先 list 看是谁、为什么，再决定同意或拒绝。"

    override val parameterHint: String =
        "JSON: {\"action\": \"list|approve|reject\", \"id\": \"list 返回的序号\", \"reason\": \"拒绝理由（可选）\"}"

    override val inputClass: Class<GroupRequestInput> = GroupRequestInput::class.java

    /** 请求队列（启动时经 [bindServices] 注入 Spring 单例） */
    private var store: PendingRequestStore? = null

    override fun bindServices(services: ToolServices) {
        store = services.pendingRequestStore
    }

    override fun handle(input: GroupRequestInput): GroupRequestResult {
        val store = this.store ?: return GroupRequestResult(
            success = false, error = "请求队列未绑定（bindServices 未调用）"
        )
        return when (input.action.trim().lowercase()) {
            "list", "" -> listResult(store)

            "approve", "reject" -> {
                val accept = input.action.trim().lowercase() == "approve"
                // 校验先于取 Bot：句柄不存在/参数缺失不需要 Bot 也能给出自愈报错
                if (input.id.isBlank()) {
                    return GroupRequestResult(
                        success = false,
                        error = "缺少 id——先用 {\"action\":\"list\"} 看待处理列表，再拿其中的序号操作"
                    )
                }
                val pending = store.find(PendingRequestStore.Kind.GROUP_INVITE, input.id)
                    ?: store.find(PendingRequestStore.Kind.GROUP_JOIN, input.id)
                    ?: return GroupRequestResult(
                        success = false,
                        error = "没有编号「${input.id}」的待处理群请求——可能已被处理，重新 list 确认"
                    )
                runTool(onError = { GroupRequestResult(success = false, error = it) }) { bot ->
                    // subType 必须原样回传（OneBot 协议：add/invite 两种处理路径）
                    val resp = bot.setGroupAddRequest(
                        pending.flag, pending.subType ?: "add", accept, input.reason
                    )
                    if (resp.status != "ok") {
                        // flag 过期（请求放置过久）等失败如实回传，由 LLM 决定放弃
                        return@runTool GroupRequestResult(
                            success = false,
                            error = "处理失败：status=${resp.status} retCode=${resp.retCode}（flag 可能已过期，请求放置过久需让对方重新发起）"
                        )
                    }
                    store.remove(pending)
                    val verb = if (accept) "同意" else "拒绝"
                    GroupRequestResult(
                        note = "已$verb ${kindLabel(pending)}：群 ${pending.groupId}（申请人 ${pending.userId}）"
                    )
                }
            }

            else -> GroupRequestResult(
                success = false,
                error = "action 必须是 list / approve / reject——正确写法：{\"action\":\"list\"}"
            )
        }
    }

    /** list：按类别分组输出，带 seq 句柄与等待时长（LLM 判断紧迫性） */
    private fun listResult(store: PendingRequestStore): GroupRequestResult {
        val all = store.listAll()
        if (all.isEmpty()) {
            return GroupRequestResult(note = "（当前没有待处理的入群邀请或加群申请）")
        }
        val text = all.joinToString("\n") { r ->
            val waited = (System.currentTimeMillis() - r.arrivedAt) / 60_000
            val who = if (r.kind == PendingRequestStore.Kind.GROUP_INVITE) "邀请人 ${r.invitorId}" else "申请人 ${r.userId}"
            val comment = r.comment?.takeIf { it.isNotBlank() }?.let { "，附言「$it」" } ?: ""
            "#${r.seq} ${kindLabel(r)}：群 ${r.groupId}，$who$comment，已等待 ${waited} 分钟"
        }
        return GroupRequestResult(note = text)
    }

    private fun kindLabel(r: PendingRequestStore.PendingRequest): String =
        if (r.kind == PendingRequestStore.Kind.GROUP_INVITE) "入群邀请" else "加群申请"
}

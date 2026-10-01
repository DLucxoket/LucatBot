package org.dlucxoket.lucatbot.bot.request

import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 待处理请求（入群邀请 / 加群申请 / 好友请求）——事件 → 队列 → LLM 决策
 *
 * 事件到达（Shiro WS 线程）时入队并记操作日志，编排层下一轮 prompt 的
 * 「你最近的操作」即可看到；智能体经 `GroupRequestTool` / `FriendRequestTool`
 * 查看（list）与处理（approve/reject），处理完成即出队。
 *
 * **不做超时自动兜底**（有意决策）：OneBot 的 flag 有时效，但请求挂太久本身
 * 就意味着机器人当时没在看——由 LLM 尽快处理是更贴合「模拟真人」的语义；
 * flag 过期时 API 会报错，工具把错误如实回给 LLM、由它放弃。
 *
 * **线程安全**：写在 Shiro 消息线程、读/删在主循环线程——[CopyOnWriteArrayList]
 * 覆盖（请求量极小，写多读少不成立也无妨），seq 用 [AtomicInteger] 单调分配。
 */
@Component
class PendingRequestStore {

    /** 请求类别 */
    enum class Kind {
        /** 被邀请进群（subType=invite） */
        GROUP_INVITE,

        /** 有人申请加入机器人管理的群（subType=add） */
        GROUP_JOIN,

        /** 好友请求 */
        FRIEND
    }

    /**
     * 一条待处理请求
     *
     * @param seq 顺序编号（1 起）——list 展示给 LLM 的**处理句柄**，
     *   approve/reject 按它定位，免得 LLM 拿不透明的 flag 字符串比对
     * @param flag OneBot 请求凭据（处理 API 的必需参数，内部字段不外露给 LLM）
     * @param subType group 请求的原生 subType（add/invite）——回传给
     *   `set_group_add_request` 时必须原样带（协议要求）
     * @param groupId 群号（group 请求）
     * @param userId 申请人 QQ（add/friend）；invite 时为邀请人
     * @param invitorId 邀请人 QQ（仅 invite；Shiro 事件单独字段）
     * @param comment 对方附言（申请理由 / 邀请留言 / 好友验证消息）
     * @param arrivedAt 到达时间（epoch ms；list 展示等待时长，LLM 判断紧迫性）
     */
    data class PendingRequest(
        val seq: Int,
        val kind: Kind,
        val flag: String,
        val subType: String? = null,
        val groupId: Long? = null,
        val userId: Long? = null,
        val invitorId: Long? = null,
        val comment: String? = null,
        val arrivedAt: Long = System.currentTimeMillis()
    )

    private val requests = CopyOnWriteArrayList<PendingRequest>()
    private val seqGen = AtomicInteger(0)

    /** 入队并分配 seq（事件线程调用） */
    @Synchronized
    fun add(
        kind: Kind,
        flag: String,
        subType: String? = null,
        groupId: Long? = null,
        userId: Long? = null,
        invitorId: Long? = null,
        comment: String? = null
    ): PendingRequest {
        val req = PendingRequest(
            seq = seqGen.incrementAndGet(),
            kind = kind,
            flag = flag,
            subType = subType,
            groupId = groupId,
            userId = userId,
            invitorId = invitorId,
            comment = comment
        )
        requests.add(req)
        return req
    }

    /** 某类别的全部待处理（按 seq 升序；新增在尾部但 seq 本身有序） */
    fun list(kind: Kind): List<PendingRequest> = requests.filter { it.kind == kind }

    /** 全部待处理（list 不带 kind 时展示，让 LLM 一次看全） */
    fun listAll(): List<PendingRequest> = requests.toList()

    /**
     * 按处理句柄定位——优先 seq 精确匹配，其次群号/QQ 号宽松匹配
     *
     * LLM 可能给「#2」「2」「群号」三种形态：seq 用字符串相等先试；
     * 不中再按 groupId/userId 数字匹配（同一编号在不同类别间不歧义，因为
     * 查询限定 [kind]）。
     *
     * @return 不在队列 → null（调用方给「先 list」的自愈报错）
     */
    fun find(kind: Kind, id: String): PendingRequest? {
        val q = id.trim().removePrefix("#")
        requests.firstOrNull { it.kind == kind && it.seq.toString() == q }?.let { return it }
        val num = q.toLongOrNull() ?: return null
        return requests.firstOrNull { it.kind == kind && (it.groupId == num || it.userId == num || it.invitorId == num) }
    }

    /** 处理完成出队（按 seq；幂等——重复移除不报错） */
    fun remove(request: PendingRequest) {
        requests.removeIf { it.kind == request.kind && it.seq == request.seq }
    }

    /** 当前积压数（日志/测试断言用） */
    fun size(): Int = requests.size
}

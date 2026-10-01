package org.dlucxoket.lucatbot.model.session

/**
 * 会话 ID 值对象——`"group:123456"` / `"private:987654"` 字符串协议的**唯一解析点**
 *
 * 此前该协议的解析（`startsWith("group:")` + `substringAfter(":")`）与拼装
 * （`"group:$id"`）散落在约 20 处（LucatBot / 两个编排 Controller / NotificationDispatcher /
 * MemorySubject / ReadStateService / 各发送类 Tool），改格式要同步约 20 地、
 * 且各处 `toLongOrNull() ?: 0L` 的兜底口径不一致——本值对象把「解析 / 拼装 / 判类型」
 * 收口为一处（OCP：改协议只改本类；SRP：只管这一件事）。
 *
 * 解析是**宽容**的（与历史行为兼容）：
 * - 无 `:` 前缀 → 按 [Type.PRIVATE] 处理，整串当作 targetId（旧代码 `startsWith("group:")`
 *   为 false、`substringAfter(":")` 返回原串，行为一致）
 * - 未知前缀（非 group/privacy）→ 同样按 PRIVATE 保留 targetId（`substringAfter` 语义）
 * - 空 targetId 照实保留，由调用方按需判空（不在此处吞掉）
 *
 * @property type 会话类型（GROUP/PRIVATE——[org.dlucxoket.lucatbot.bot.agent.data.SessionPickType]
 *   的 PROACTIVE 在落会话 ID 时即归约为 PRIVATE）
 * @property targetId 群号/对方 QQ 号的字符串形态（保留原样，避免非法输入处丢失）
 */
data class ConversationId(
    val type: Type,
    val targetId: String
) {
    /** 会话类型 */
    enum class Type {
        /** 群聊 */
        GROUP,

        /** 私聊（含主动发起的 proactive——落 ID 后无从区分，也没有区分的必要） */
        PRIVATE
    }

    /** 是否群聊 */
    val isGroup: Boolean get() = type == Type.GROUP

    /** targetId 的数字形态；非数字返回 null（调用方自行决定兜底值） */
    val targetIdOrNull: Long? get() = targetId.toLongOrNull()

    /** 拼回线格式 `"group:123456"`（未读队列键、记忆主体、档案索引等处用） */
    fun asKey(): String = "$typePrefix$targetId"

    /** 线格式前缀（小写） */
    private val typePrefix: String get() = if (isGroup) GROUP_PREFIX else PRIVATE_PREFIX

    override fun toString(): String = asKey()

    companion object {
        /** 群会话键前缀（含冒号）——需要**严格**判断「是不是群键」时用它，勿手写字符串 */
        const val GROUP_PREFIX = "group:"

        /** 私聊键前缀（含冒号）——同上；严格前缀判断（非宽容 [parse]）走这里 */
        const val PRIVATE_PREFIX = "private:"

        /**
         * 解析线格式会话 ID
         *
         * 宽容语义见类 KDoc；本函数是全项目唯一允许碰 `":"` 分隔符的地方。
         */
        fun parse(raw: String): ConversationId {
            if (!raw.startsWith(GROUP_PREFIX)) {
                // 无前缀或未知前缀都按私聊保留原样——与旧 `startsWith` + `substringAfter` 组合一致
                return ConversationId(Type.PRIVATE, raw.substringAfter(":"))
            }
            return ConversationId(Type.GROUP, raw.substringAfter(":"))
        }

        /** 拼一个群会话 ID */
        fun group(targetId: Any): ConversationId =
            ConversationId(Type.GROUP, targetId.toString())

        /** 拼一个私聊会话 ID */
        fun private(targetId: Any): ConversationId =
            ConversationId(Type.PRIVATE, targetId.toString())
    }
}

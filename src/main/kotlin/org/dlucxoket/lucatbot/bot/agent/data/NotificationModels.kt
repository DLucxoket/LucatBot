package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona

/**
 * 通知决策请求（新消息到达时的「手机横幅」——由 NotificationAgent 判断）
 *
 * 模拟真人收到通知横幅瞄一眼：点开（open）/ 划掉（ignore）/ 记下来回头处理（todo）。
 *
 * @param stateDescription 机器人当前状态（空闲 / 在看消息列表 / 正在与XX聊天）
 * @param persona 人格
 * @param mood 心情
 * @param timeHint 当前时间
 * @param currentChat 处于聊天界面时的当前聊天记录摘要（判断打断价值）；其他状态空
 * @param notifyConversationType 通知所在会话类型（私聊/群聊）
 * @param notifyConversationId 通知所在会话 id（open 跳转用）
 * @param notifyConversationName 通知所在会话名
 * @param senderName 发送者展示名
 * @param senderId 发送者 QQ 号
 * @param messageContent 消息内容（rich 标记原文）
 * @param isAtAll 是否 @全体成员
 * @param isAtMe 是否 @了机器人
 * @param todos 现有待办摘要（todo 判断防重复）
 * @param shortTermMemory 短期记忆概览（判断通知重要性）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NotificationRequest(
    @JsonProperty("stateDescription") val stateDescription: String = "",
    @JsonProperty("persona") val persona: BotPersona = BotPersona(),
    @JsonProperty("mood") val mood: BotMood = BotMood(),
    @JsonProperty("timeHint") val timeHint: String = "",
    @JsonProperty("currentChat") val currentChat: String = "",
    @JsonProperty("notifyConversationType") val notifyConversationType: String = "",
    @JsonProperty("notifyConversationId") val notifyConversationId: String = "",
    @JsonProperty("notifyConversationName") val notifyConversationName: String = "",
    @JsonProperty("senderName") val senderName: String = "",
    @JsonProperty("senderId") val senderId: String = "",
    @JsonProperty("messageContent") val messageContent: String = "",
    @JsonProperty("isAtAll") val isAtAll: Boolean = false,
    @JsonProperty("isAtMe") val isAtMe: Boolean = false,
    @JsonProperty("todos") val todos: String = "",
    @JsonProperty("shortTermMemory") val shortTermMemory: String = ""
)

/** 通知决策动作（wire 词表单一源，同 ConversationAction/SessionAction） */
enum class NotificationAction(val wire: String) {
    /** 划掉：现在不处理（消息已入档+未读，之后扫列表仍可见） */
    IGNORE("ignore"),

    /** 记下来：创建一条待办，稍后处理 */
    TODO("todo"),

    /** 点开：当场跳转到通知所在会话，交给会话内决策 agent */
    OPEN("open");

    companion object {
        /** 别名 → 规范动作（模型偶发写法） */
        val ALIASES: Map<String, NotificationAction> = mapOf(
            "dismiss" to IGNORE,
            "jump" to OPEN
        )

        /** 完整可识别词表（规范名 + 别名）——prompt 动作行与解析共用（计算属性，同 ConversationAction） */
        val WIRE_NAMES: Set<String>
            get() = NotificationAction.entries.mapTo(mutableSetOf<String>()) { it.wire } + ALIASES.keys

        /** 线上动作名 → 枚举（调用方已 lowercase）；未收录 → null（走解析失败兜底） */
        fun fromWire(name: String): NotificationAction? =
            NotificationAction.entries.firstOrNull { it.wire == name } ?: ALIASES[name]

        /**
         * prompt 动作行文本——**渲染结果必须与历史字面量逐字一致**。
         * 参数同 `ConversationAction.promptList`
         */
        fun promptList(separator: String = " | ", quoted: Boolean = true): String =
            NotificationAction.entries.joinToString(separator) {
                if (quoted) "\"${it.wire}\"" else it.wire
            }
    }
}

/**
 * 通知决策结果（统一信封的 OOP 形态）
 *
 * 解析失败兜底 [IGNORE]——通知消息已入档并进未读队列，保守无视不丢消息、
 * 不打断现状。
 */
data class NotificationDecision(
    val action: NotificationAction,
    val params: Map<String, Any?> = emptyMap(),
    val reason: String? = null
) {
    /** todo 的待办内容（params.content） */
    val todoContent: String?
        get() = params["content"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(NotificationDecision::class.java)

        /** 解析失败/缺参兜底：保守无视（消息仍在未读里不丢） */
        val FALLBACK = NotificationDecision(action = NotificationAction.IGNORE, reason = "解析失败兜底")

        /** 兜底并记 LLM 原文（截断）——失败时能看到模型说了什么 */
        private fun fail(raw: String, why: String): NotificationDecision {
            log.warn("通知决策解析失败（{}）——LLM 原文: {}", why, raw.take(500))
            return FALLBACK
        }

        /** 从 LLM 输出的统一信封解析 */
        fun parse(llmOutput: String): NotificationDecision {
            val node = LlmJson.parseTree(llmOutput)
                ?: return fail(llmOutput, "非 JSON 或无可提取 JSON")
            val actionText = node.get("action")?.asText()?.trim()?.lowercase()
                ?: return fail(llmOutput, "缺 action 字段")
            val action = NotificationAction.fromWire(actionText)
                ?: return fail(llmOutput, "未知 action=$actionText")
            val params = LlmJson.paramsOf(node)
            // todo 必须带内容，否则视为无效 → 保守无视（消息仍在未读里不丢）
            if (action == NotificationAction.TODO && params["content"]?.toString().isNullOrBlank()) {
                return fail(llmOutput, "todo 缺 content")
            }
            return NotificationDecision(
                action = action,
                params = params,
                reason = node.get("reason")?.takeIf { !it.isNull }?.asText()
            )
        }
    }
}

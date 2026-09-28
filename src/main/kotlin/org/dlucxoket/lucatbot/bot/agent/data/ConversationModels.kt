package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 统一决策信封（消息列表 / 会话内共用）
 *
 * LLM 只输出 `{action, params, reason}`；params 内是工具入参；
 * reason 是内心动机（不进 params、不进工具、不进操作日志）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Decision(
    @JsonProperty("action")
    val action: String = "",

    @JsonProperty("params")
    val params: Map<String, Any?> = emptyMap(),

    @JsonProperty("reason")
    val reason: String? = null
)

/**
 * 会话内动作
 */
enum class ConversationAction {
    /** 爬楼：再看更早的历史 */
    READ_MORE,

    /** 发一条消息 */
    SEND,

    /** 退出本会话，回到消息列表 */
    RETURN
}

/**
 * 会话内决策结果（统一信封的 OOP 形态）
 *
 * @param action 动作
 * @param params 工具入参：send → content；read_more → count
 * @param reason 内心动机（不会发出去）
 */
data class ConversationDecision(
    val action: ConversationAction,
    val params: Map<String, Any?> = emptyMap(),
    val reason: String? = null
) {
    /** send 的正文（params.content） */
    val content: String? get() = params["content"] as? String

    /** read_more 的条数（params.count，默认 10） */
    val readCount: Int get() = (params["count"] as? Number)?.toInt() ?: 10

    companion object {
        /** 解析失败兜底：RETURN，避免卡死在会话里 */
        val FALLBACK = ConversationDecision(
            action = ConversationAction.RETURN,
            reason = "解析失败兜底退出"
        )

        /** 连续 read_more 超限时的强制动作 */
        fun forceReturn(reason: String) = ConversationDecision(
            action = ConversationAction.RETURN,
            reason = reason
        )

        /**
         * 从 LLM 输出的统一信封解析
         */
        fun parse(llmOutput: String): ConversationDecision {
            val node = LlmJson.parseTree(llmOutput) ?: return FALLBACK
            val actionText = node.get("action")?.asText()?.trim()?.lowercase() ?: return FALLBACK
            val action = when (actionText) {
                "read_more", "readmore" -> ConversationAction.READ_MORE
                "send" -> ConversationAction.SEND
                "return" -> ConversationAction.RETURN
                else -> return FALLBACK
            }
            val params = LlmJson.paramsOf(node)
            val reason = node.get("reason")?.takeIf { !it.isNull }?.asText()
            // SEND 必须带正文，否则视为无效决策兜底退出
            if (action == ConversationAction.SEND && params["content"]?.toString().isNullOrBlank()) {
                return FALLBACK
            }
            return ConversationDecision(action = action, params = params, reason = reason)
        }
    }
}

/**
 * 聊天记录行（注入决策 prompt）
 */
data class ConversationChatLine(
    /** 发送者显示名；机器人为「我」 */
    val sender: String = "",
    val senderId: String = "",
    val isMe: Boolean = false,
    /** 展示用时间 */
    val time: String = "",
    val content: String = ""
)

/**
 * 会话内决策请求
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ConversationRequest(
    @JsonProperty("persona")
    val persona: org.dlucxoket.lucatbot.bot.persona.BotPersona =
        org.dlucxoket.lucatbot.bot.persona.BotPersona(),

    @JsonProperty("mood")
    val mood: org.dlucxoket.lucatbot.bot.persona.BotMood =
        org.dlucxoket.lucatbot.bot.persona.BotMood(),

    @JsonProperty("timeHint")
    val timeHint: String = "",

    @JsonProperty("conversationType")
    val conversationType: String = "私聊",

    @JsonProperty("conversationName")
    val conversationName: String = "",

    @JsonProperty("conversationId")
    val conversationId: String = "",

    @JsonProperty("shortTermMemory")
    val shortTermMemory: String = "",

    /** 滚动操作日志（纯 string，多行） */
    @JsonProperty("actionLog")
    val actionLog: String = "",

    @JsonProperty("recentMessages")
    val recentMessages: List<ConversationChatLine> = emptyList(),

    /** 待插入新消息的系统注释行 */
    @JsonProperty("newMessageNotes")
    val newMessageNotes: List<String> = emptyList(),

    /** 主动发起的会话：必须先 send 破冰 */
    @JsonProperty("proactive")
    val proactive: Boolean = false,

    /** 本次会话已爬楼（read_more）的次数 */
    @JsonProperty("readMoreRounds")
    val readMoreRounds: Int = 0,

    /** 已覆盖最近多少条聊天记录（初始 N + 每次爬楼累加） */
    @JsonProperty("historyCovered")
    val historyCovered: Int = 0,

    /** 是否已翻到最早消息（再 read_more 也没有更早的历史） */
    @JsonProperty("reachedHistoryStart")
    val reachedHistoryStart: Boolean = false,

    /** 强制约束提示（如「有人发了新消息，优先回应」） */
    @JsonProperty("hint")
    val hint: String = ""
)

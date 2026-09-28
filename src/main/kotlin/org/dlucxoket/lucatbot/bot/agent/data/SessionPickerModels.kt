package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona

/**
 * 会话选择类型
 */
enum class SessionPickType {
    /** 被动：打开某个私聊 */
    PRIVATE,

    /** 被动：打开某个群聊 */
    GROUP,

    /** 主动：去私聊某人（进入后首条由机器人先说） */
    PROACTIVE
}

/**
 * 选中的会话（none / idle 时用 null 表达，不出现 "none" 字符串）
 *
 * @param conversationType 会话类型
 * @param targetId 对象 ID
 */
data class SessionPick(
    val conversationType: SessionPickType,
    val targetId: String
)

/**
 * 未读/候选会话摘要（注入选会话 prompt）
 */
data class SessionSummaryLine(
    /** "私聊" / "群聊" */
    val conversationType: String = "",
    val name: String = "",
    val id: String = "",
    /** 展示用时间 */
    val time: String = "",
    val unreadCount: Int = 0,
    val lastMessage: String = "",
    val isAtMe: Boolean = false,
    val isAtAll: Boolean = false
)

/**
 * 可主动找的人（仅空闲唤醒时出现）
 */
data class ProactiveCandidate(
    val name: String = "",
    val id: String = "",
    /** 亲密度/近况提示，如「上次聊到 xxx / 亲密度高 / 这个点他可能在」 */
    val hint: String = ""
)

/**
 * 会话选择请求（JSON 只在 LLM 边界；这里全程 OOP）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionPickerRequest(
    @JsonProperty("persona")
    val persona: BotPersona = BotPersona(),

    @JsonProperty("mood")
    val mood: BotMood = BotMood(),

    @JsonProperty("timeHint")
    val timeHint: String = "",

    @JsonProperty("shortTermMemory")
    val shortTermMemory: String = "",

    /** 滚动操作日志（纯 string，多行） */
    @JsonProperty("actionLog")
    val actionLog: String = "",

    @JsonProperty("sessions")
    val sessions: List<SessionSummaryLine> = emptyList(),

    /** 可主动找的人；空列表表示不展示该区块 */
    @JsonProperty("proactiveCandidates")
    val proactiveCandidates: List<ProactiveCandidate> = emptyList()
)

/**
 * 从统一信封解析 SessionPick
 *
 * action=open_conversation → SessionPick；action=idle → null。
 */
object SessionPickParser {
    fun parse(llmOutput: String): SessionPick? {
        val node = LlmJson.parseTree(llmOutput) ?: return null
        val action = node.get("action")?.asText()?.trim()?.lowercase() ?: return null
        if (action != "open_conversation") return null

        val params = LlmJson.paramsOf(node)
        val typeText = params["type"]?.toString()?.trim()?.lowercase() ?: return null
        val target = params["target"]?.toString()?.trim().orEmpty()
        if (target.isEmpty()) return null

        val type = when (typeText) {
            "private" -> SessionPickType.PRIVATE
            "group" -> SessionPickType.GROUP
            "proactive" -> SessionPickType.PROACTIVE
            else -> return null
        }
        return SessionPick(conversationType = type, targetId = target)
    }
}

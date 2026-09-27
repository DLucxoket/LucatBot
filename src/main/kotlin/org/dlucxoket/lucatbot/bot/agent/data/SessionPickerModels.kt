package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona

/**
 * 会话选择请求
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionPickerRequest(
    @JsonProperty("unreadSummary")
    val unreadSummary: String = "",

    @JsonProperty("persona")
    val persona: BotPersona = BotPersona(),

    @JsonProperty("mood")
    val mood: BotMood = BotMood(),

    @JsonProperty("timeHint")
    val timeHint: String = "",

    /** 兼容字段：LLM 可能传原始上下文 */
    @JsonProperty("context")
    val context: String = ""
)

/**
 * 会话选择结果
 */
data class SessionPickerResult(
    @JsonProperty("selected")
    val selected: String = "none",  // 会话 ID 或 "none"

    @JsonProperty("reason")
    val reason: String = ""
)

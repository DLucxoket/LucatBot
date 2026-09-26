package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonProperty
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona

/**
 * 会话选择请求
 *
 * 模拟真人做决策时拥有的全部信息。
 */
data class SessionPickerRequest(
    @JsonProperty("unreadSummary")
    val unreadSummary: String = "",

    @JsonProperty("persona")
    val persona: BotPersona = BotPersona(),

    @JsonProperty("mood")
    val mood: BotMood = BotMood(),

    @JsonProperty("timeHint")
    val timeHint: String = ""
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

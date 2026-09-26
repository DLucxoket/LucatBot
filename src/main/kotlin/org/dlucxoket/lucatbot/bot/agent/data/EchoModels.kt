package org.dlucxoket.lucatbot.bot.agent.data

/**
 * 回声 Agent 的请求（测试用）
 */
data class EchoRequest(
    val text: String
)

/**
 * 回声 Agent 的响应
 */
data class EchoResult(
    val echo: String
)

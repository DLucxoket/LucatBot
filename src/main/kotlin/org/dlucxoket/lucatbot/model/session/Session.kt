package org.dlucxoket.lucatbot.model.session

import org.springframework.stereotype.Component

/**
 * 会话上下文管理器
 *
 * **模拟真人的对话记忆**：
 * - 记住每个会话的最近聊天内容
 * - 回复时参考之前的对话（不是每次都从零开始）
 * - 像真人一样：记得刚才说过什么
 *
 * 存储每个会话的最近 N 条消息，供 Agent 调用时参考。
 */
@Component
class SessionManager {

    /**
     * 单条会话消息
     */
    data class SessionEntry(
        val sender: String,
        val content: String,
        val isBot: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 会话上下文
     */
    data class Session(
        val conversationId: String,
        val entries: MutableList<SessionEntry> = mutableListOf(),
        val lastActiveTime: Long = System.currentTimeMillis()
    )

    /** 会话存储 */
    private val sessions = mutableMapOf<String, Session>()

    /** 每个会话最多保留的历史条数 */
    private val maxHistory = 20

    /**
     * 记录一条消息
     */
    fun addMessage(conversationId: String, sender: String, content: String, isBot: Boolean = false) {
        val session = sessions.getOrPut(conversationId) { Session(conversationId) }
        session.entries.add(SessionEntry(sender, content, isBot))
        // 只保留最近 maxHistory 条
        if (session.entries.size > maxHistory) {
            session.entries.removeAt(0)
        }
    }

    /**
     * 获取会话历史（最近 N 条）
     */
    fun getHistory(conversationId: String, limit: Int = 10): List<SessionEntry> {
        return sessions[conversationId]?.entries?.takeLast(limit) ?: emptyList()
    }

    /**
     * 获取会话上下文摘要（供 LLM 使用）
     */
    fun getContextSummary(conversationId: String): String {
        val history = getHistory(conversationId)
        if (history.isEmpty()) return "（新会话）"

        return history.joinToString("\n") { entry ->
            val role = if (entry.isBot) "我" else entry.sender
            "$role: ${entry.content}"
        }
    }

    /**
     * 获取最后一条消息
     */
    fun getLastMessage(conversationId: String): SessionEntry? {
        return sessions[conversationId]?.entries?.lastOrNull()
    }

    /**
     * 判断会话是否活跃（最近有对话）
     */
    fun isActive(conversationId: String, withinMinutes: Int = 30): Boolean {
        val session = sessions[conversationId] ?: return false
        val elapsed = System.currentTimeMillis() - session.lastActiveTime
        return elapsed < withinMinutes * 60 * 1000
    }
}

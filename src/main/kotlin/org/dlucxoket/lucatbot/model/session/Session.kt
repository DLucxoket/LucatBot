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
        val senderId: Long = 0L,
        val content: String,
        val isBot: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 会话上下文
     */
    data class Session(
        val conversationId: String,
        val entries: MutableList<SessionEntry> = java.util.concurrent.CopyOnWriteArrayList(),
        val lastActiveTime: Long = System.currentTimeMillis()
    )

    /** 会话存储（线程安全） */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()

    /** 每个会话最多保留的历史条数 */
    private val maxHistory = 20

    /**
     * 记录一条消息
     */
    fun addMessage(conversationId: String, sender: String, content: String, isBot: Boolean = false, senderId: Long = 0L) {
        val session = sessions.getOrPut(conversationId) { Session(conversationId) }
        session.entries.add(SessionEntry(sender, senderId, content, isBot))
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
            val role = if (entry.isBot) "我" else "${entry.sender}(${entry.senderId})"
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

    /**
     * 最近活跃过的会话 ID 列表（按最近活跃时间倒序）
     *
     * 用于消息列表展示与「可主动找的人」候选来源。
     */
    fun recentConversationIds(withinMinutes: Int = 24 * 60): List<String> {
        val cutoff = System.currentTimeMillis() - withinMinutes * 60 * 1000L
        return sessions.values
            .filter { it.lastActiveTime >= cutoff }
            .sortedByDescending { it.lastActiveTime }
            .map { it.conversationId }
    }

    /**
     * 会话疲劳度（0.0-1.0）
     *
     * 聊得越多越疲劳，模拟真人聊久了会变敷衍。
     * - 0 条消息 → 0.0（新鲜）
     * - 20 条消息 → 1.0（很疲劳）
     */
    fun getFatigue(conversationId: String): Double {
        val session = sessions[conversationId] ?: return 0.0
        return (session.entries.size.toDouble() / maxHistory).coerceIn(0.0, 1.0)
    }

    /**
     * 获取疲劳提示（注入 LLM prompt）
     */
    fun getFatigueHint(conversationId: String): String {
        val fatigue = getFatigue(conversationId)
        return when {
            fatigue < 0.3 -> ""
            fatigue < 0.6 -> "（聊了一会儿了）"
            fatigue < 0.8 -> "（聊得有点久了，回复可以简短些）"
            else -> "（聊很久了，有点敷衍，回复尽量简短甚至用'嗯''哈哈'应付）"
        }
    }
}

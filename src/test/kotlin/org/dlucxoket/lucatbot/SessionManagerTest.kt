package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.model.session.SessionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * lastActiveTime 活跃时间回归测试。
 *
 * 回归背景：Session.lastActiveTime 曾是 val、只在构造时赋值，导致
 * isActive / recentConversationIds 只认「会话创建时间」。
 *
 * 注意：类名不能叫 SessionManagerTest（与 LucatBotTests.kt 中的同名类冲突）。
 */
class SessionManagerActivityTest {

    private val manager = SessionManager()

    /** 反射取内部 sessions，把 lastActiveTime 回拨，模拟「老会话」 */
    private fun backdate(conversationId: String, minutesAgo: Long) {
        val field = SessionManager::class.java.getDeclaredField("sessions")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = field.get(manager) as Map<String, SessionManager.Session>
        val session = sessions[conversationId] ?: error("session not found: $conversationId")
        session.lastActiveTime = System.currentTimeMillis() - minutesAgo * 60_000L
    }

    @Test
    fun `addMessage refreshes lastActiveTime so isActive becomes true again`() {
        manager.addMessage("private:1", "Alice", "你好")
        backdate("private:1", minutesAgo = 60)
        assertFalse(manager.isActive("private:1", withinMinutes = 30), "回拨 60 分钟后应判为不活跃")

        manager.addMessage("private:1", "Alice", "在吗")
        assertTrue(manager.isActive("private:1", withinMinutes = 30), "addMessage 后应刷新活跃时间")
    }

    @Test
    fun `recentConversationIds contains old session after new message`() {
        manager.addMessage("private:9", "Alice", "很久以前")
        backdate("private:9", minutesAgo = 48 * 60)
        assertFalse(manager.recentConversationIds(withinMinutes = 60).contains("private:9"), "老会话不应出现在窗口内")

        manager.addMessage("private:9", "Alice", "刚刚聊的")
        assertTrue(manager.recentConversationIds(withinMinutes = 60).contains("private:9"), "刚聊过的老会话应出现")
    }

    @Test
    fun `recentConversationIds sorted newest first`() {
        manager.addMessage("private:a", "user", "a")
        manager.addMessage("private:b", "user", "b")
        backdate("private:a", minutesAgo = 1)
        assertEquals(
            listOf("private:b", "private:a"),
            manager.recentConversationIds(withinMinutes = 60)
        )
    }

    @Test
    fun `isActive false for unknown conversation`() {
        assertFalse(manager.isActive("private:none", withinMinutes = 30))
    }

    @Test
    fun `history trimmed to maxHistory keeping newest`() {
        repeat(25) { i -> manager.addMessage("private:2", "user", "msg$i") }
        val history = manager.getHistory("private:2", limit = 100)
        assertEquals(20, history.size, "超过 maxHistory(20) 应裁剪")
        assertEquals("msg5", history.first().content, "最老的条目应被移除")
        assertEquals("msg24", history.last().content, "最新条目应保留")
    }
}

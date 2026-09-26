package org.dlucxoket.lucatbot.bot

import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona
import org.dlucxoket.lucatbot.model.event.message.MessageEvent
import org.springframework.stereotype.Component

/**
 * 机器人本体——状态机
 *
 * **模拟真人在 QQ 中的真实行为**：
 * - 没在管 QQ 时：消息只进未读队列，不处理
 * - 在会话列表时：看到未读概况，决定进哪个会话
 * - 在某个会话时：只处理该会话的消息，其他消息进未读
 *
 * 核心规则：
 * - **一次只关注一个会话**（真人不可能同时看两个）
 * - 在 A 会话时，B 会话的普通消息 → 未读队列，不处理
 * - 在 A 会话时，B 会话 @我/@全体 → 可能打断（取决于优先级）
 */
@Component
class LucatBot {

    /**
     * 机器人当前状态
     */
    sealed class State {
        /** 没在管 QQ（离线/挂机） */
        object Idle : State()

        /** 在会话列表（扫一眼有哪些未读） */
        object ViewingConversations : State()

        /** 在某个会话界面中 */
        data class InConversation(
            val conversationId: String,       // 如 "private:123456" 或 "group:789"
            val conversationType: ConversationType
        ) : State()
    }

    /**
     * 会话类型
     */
    enum class ConversationType { PRIVATE, GROUP }

    /**
     * 消息处理动作
     */
    enum class MessageAction {
        /** 完全忽略（加入未读） */
        IGNORE,

        /** 处理消息（当前会话） */
        PROCESS,

        /** 通知（@我/@全体，可能打断当前会话） */
        NOTIFY
    }

    /**
     * 未读消息
     */
    data class UnreadMessage(
        val conversationId: String,
        val conversationType: ConversationType,
        val senderId: Long,
        val senderName: String,
        val content: String,
        val isAtMe: Boolean = false,
        val isAtAll: Boolean = false,
        val timestamp: Long = 0L
    )

    /** 当前状态 */
    var state: State = State.Idle

    /** 机器人人格 */
    val persona: BotPersona = BotPersona()

    /** 当前心情 */
    var mood: BotMood = BotMood.guessByTime()

    /** 未读消息队列（线程安全） */
    private val unreadQueue = java.util.concurrent.CopyOnWriteArrayList<UnreadMessage>()

    /**
     * 判断一条消息该怎么处理
     *
     * 根据当前状态和消息特征决定：
     * - 当前会话的消息 → PROCESS
     * - 其他会话 @我/@全体 → NOTIFY
     * - 其他会话普通消息 → IGNORE（进未读）
     *
     * @param event 消息事件
     * @param isAtMe 是否 @了机器人
     * @param isAtAll 是否 @全体成员
     * @return 处理动作
     */
    fun handleMessage(event: MessageEvent, isAtMe: Boolean, isAtAll: Boolean): MessageAction {
        val conversationId = buildConversationId(event)

        return when (state) {
            // 没在管 QQ → 全部进未读
            is State.Idle -> {
                addUnread(event, conversationId, isAtMe, isAtAll)
                MessageAction.IGNORE
            }

            // 在会话列表 → 看情况
            is State.ViewingConversations -> {
                if (isAtMe || isAtAll) {
                    MessageAction.NOTIFY
                } else {
                    addUnread(event, conversationId, isAtMe, isAtAll)
                    MessageAction.IGNORE
                }
            }

            // 在某个会话中
            is State.InConversation -> {
                val current = state as State.InConversation
                if (conversationId == current.conversationId) {
                    // 当前会话的消息 → 处理
                    MessageAction.PROCESS
                } else if (isAtMe || isAtAll) {
                    // 其他会话但 @我/@全体 → 通知（模拟真人被打断）
                    MessageAction.NOTIFY
                } else {
                    // 其他会话普通消息 → 未读（模拟真人不管）
                    addUnread(event, conversationId, isAtMe, isAtAll)
                    MessageAction.IGNORE
                }
            }
        }
    }

    /**
     * 进入某个会话
     */
    fun enterConversation(conversationId: String, type: ConversationType) {
        state = State.InConversation(conversationId, type)
    }

    /**
     * 回到会话列表
     */
    fun backToConversationList() {
        state = State.ViewingConversations
    }

    /**
     * 离开 QQ（挂机）
     */
    fun goIdle() {
        state = State.Idle
    }

    /**
     * 获取未读概况（会话 → 未读条数）
     */
    fun getUnreadSummary(): Map<String, Int> {
        return unreadQueue.groupBy { it.conversationId }
            .mapValues { it.value.size }
    }

    /**
     * 某个会话是否有 @我 的未读
     */
    fun hasUnreadAtMe(conversationId: String): Boolean {
        return unreadQueue.any { it.conversationId == conversationId && it.isAtMe }
    }

    /**
     * 某个会话是否有 @全体 的未读
     */
    fun hasUnreadAtAll(conversationId: String): Boolean {
        return unreadQueue.any { it.conversationId == conversationId && it.isAtAll }
    }

    /**
     * 获取并清除某个会话的未读消息
     */
    fun consumeUnread(conversationId: String): List<UnreadMessage> {
        val messages = unreadQueue.filter { it.conversationId == conversationId }
        unreadQueue.removeAll { it.conversationId == conversationId }
        return messages
    }

    /**
     * 加入未读队列
     */
    private fun addUnread(event: MessageEvent, conversationId: String, isAtMe: Boolean, isAtAll: Boolean) {
        unreadQueue.add(
            UnreadMessage(
                conversationId = conversationId,
                conversationType = if (conversationId.startsWith("group:")) ConversationType.GROUP else ConversationType.PRIVATE,
                senderId = event.sender.qqNumber,
                senderName = event.sender.nickname,
                content = extractText(event),
                isAtMe = isAtMe,
                isAtAll = isAtAll,
                timestamp = event.timestamp
            )
        )
    }

    /**
     * 构建会话 ID
     */
    private fun buildConversationId(event: MessageEvent): String {
        return if (event is org.dlucxoket.lucatbot.model.event.message.GroupMessageEvent) {
            "group:${event.group.groupNumber}"
        } else {
            "private:${event.sender.qqNumber}"
        }
    }

    /**
     * 提取消息文本
     */
    private fun extractText(event: MessageEvent): String {
        val msg = event.message
        return if (msg is org.dlucxoket.lucatbot.model.message.ChatMessage) {
            msg.messageMembers
                .filterIsInstance<org.dlucxoket.lucatbot.model.message.member.TextMessageMember>()
                .joinToString("") { it.text }
        } else {
            ""
        }
    }
}

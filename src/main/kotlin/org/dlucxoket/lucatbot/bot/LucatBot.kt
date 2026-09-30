package org.dlucxoket.lucatbot.bot

import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona
import org.dlucxoket.lucatbot.bot.persona.PersonaLoader
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
        val timestamp: Long = 0L,
        /** OneBot message_id（待插入注释渲染成「（#id）」，供 LLM 直接回复新消息） */
        val messageId: Long? = null,
        /** 图片/表情包原始地址（与 content 占位符同序，图片通道附件下载用） */
        val imageUrls: List<String>? = null
    )

    /** 当前状态 */
    var state: State = State.Idle

    /** 机器人人格（启动时从 persona.md 读取，见 [org.dlucxoket.lucatbot.bot.persona.PersonaLoader]） */
    val persona: BotPersona = PersonaLoader.persona

    /** 当前心情 */
    var mood: BotMood = BotMood.guessByTime()

    /** 未读消息队列（线程安全） */
    private val unreadQueue = java.util.concurrent.CopyOnWriteArrayList<UnreadMessage>()

    /**
     * 当前会话的待插入新消息队列
     *
     * 会话内决策循环进行中到来的消息先进这里，不打断 LLM 调用；
     * 当前决策返回后由 [drainPendingInserts] 以系统注释形式合并进上下文。
     */
    private val pendingInsertQueue = java.util.concurrent.CopyOnWriteArrayList<UnreadMessage>()

    /**
     * 待插入队列的监视器：入队时 notifyAll，[awaitPendingInsert] 等待 STAY 期间的新消息
     */
    private val pendingInsertLock = Object()

    /** 当前 Shiro Bot 实例（用于发送消息） */
    @Volatile
    var shiroBot: com.mikuac.shiro.core.Bot? = null

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
        val conversationId = event.conversationId

        return when (state) {
            // 没在管 QQ → 全部进未读，心情衰减
            is State.Idle -> {
                addUnread(event, conversationId, isAtMe, isAtAll)
                mood = mood.decay()
                MessageAction.IGNORE
            }

            // 在会话列表 → 看情况
            is State.ViewingConversations -> {
                if (isAtMe || isAtAll) {
                    mood = mood.boost()  // 有人找我，心情好一点
                    // 通知类消息同样入未读：通知通道判「无视」后，选会话侧
                    // 仍要能看到它（判「跳转」进会话时 consumeUnread 自动清）
                    addUnread(event, conversationId, isAtMe, isAtAll)
                    MessageAction.NOTIFY
                } else {
                    addUnread(event, conversationId, isAtMe, isAtAll)
                    mood = mood.decay()
                    MessageAction.IGNORE
                }
            }

            // 在某个会话中
            is State.InConversation -> {
                @Suppress("UNCHECKED_CAST")
                val current = state as State.InConversation
                if (conversationId == current.conversationId) {
                    // 当前会话的消息 → 进待插入队列，不打断决策循环
                    mood = mood.boost()
                    addPendingInsert(event, conversationId, isAtMe, isAtAll)
                    MessageAction.PROCESS
                } else if (isAtMe || isAtAll) {
                    // 其他会话但 @我/@全体 → 通知通道（同样入未读，无视可见/跳转即清）
                    mood = mood.boost()
                    addUnread(event, conversationId, isAtMe, isAtAll)
                    MessageAction.NOTIFY
                } else {
                    // 其他会话普通消息 → 未读（被冷落，心情微降）
                    addUnread(event, conversationId, isAtMe, isAtAll)
                    mood = mood.drop()
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

    /** 窥视未读消息（不清除） */
    fun peekUnread(conversationId: String): List<UnreadMessage> {
        return unreadQueue.filter { it.conversationId == conversationId }
    }

    /**
     * 当前会话来了新消息 → 进待插入队列（不打断正在进行的 LLM 调用），
     * 并唤醒正在 [awaitPendingInsert] 等待的会话循环
     */
    private fun addPendingInsert(event: MessageEvent, conversationId: String, isAtMe: Boolean, isAtAll: Boolean) {
        enqueuePendingInsert(
            conversationId = conversationId,
            senderId = event.sender.qqNumber,
            senderName = event.sender.displayName,
            content = extractRichContent(event),
            timestamp = event.timestamp,
            isAtMe = isAtMe,
            isAtAll = isAtAll,
            messageId = event.messageId,
            imageUrls = (event.message as? org.dlucxoket.lucatbot.model.message.ChatMessage)?.assetUrls()
                ?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * 取走当前会话的待插入消息（决策间隙合并进上下文）
     */
    fun drainPendingInserts(conversationId: String): List<UnreadMessage> {
        val messages = pendingInsertQueue.filter { it.conversationId == conversationId }
        pendingInsertQueue.removeAll { it.conversationId == conversationId }
        return messages
    }

    /**
     * 等待当前会话的新消息或超时（供会话内 STAY 计时使用）
     *
     * 计时未到但当前会话来了新消息 → 立即返回 true（取消剩余计时，唤醒机器人）；
     * 等满 [timeoutMs] 仍无新消息 → 返回 false。
     *
     * @return true = 被新消息提前唤醒；false = 等满超时
     */
    fun awaitPendingInsert(conversationId: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(pendingInsertLock) {
            while (true) {
                if (pendingInsertQueue.any { it.conversationId == conversationId }) return true
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                pendingInsertLock.wait(remaining)
            }
        }
    }

    /**
     * 直接往待插入队列塞一条新消息（编排层/测试用），并唤醒等待中的会话循环
     */
    fun enqueuePendingInsert(
        conversationId: String,
        senderId: Long,
        senderName: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        isAtMe: Boolean = false,
        isAtAll: Boolean = false,
        messageId: Long? = null,
        imageUrls: List<String>? = null
    ) {
        synchronized(pendingInsertLock) {
            pendingInsertQueue.add(
                UnreadMessage(
                    conversationId = conversationId,
                    conversationType = if (conversationId.startsWith("group:")) ConversationType.GROUP else ConversationType.PRIVATE,
                    senderId = senderId,
                    senderName = senderName,
                    content = content,
                    isAtMe = isAtMe,
                    isAtAll = isAtAll,
                    timestamp = timestamp,
                    messageId = messageId,
                    imageUrls = imageUrls
                )
            )
            pendingInsertLock.notifyAll()
        }
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
                senderName = event.sender.displayName,
                content = extractRichContent(event),
                isAtMe = isAtMe,
                isAtAll = isAtAll,
                timestamp = event.timestamp,
                messageId = event.messageId
            )
        )
    }

    /**
     * 提取消息富内容（委托 model 层 describe，含 @/reply/图片/表情）
     */
    private fun extractRichContent(event: MessageEvent): String {
        val msg = event.message
        return if (msg is org.dlucxoket.lucatbot.model.message.ChatMessage) {
            msg.describe()
        } else {
            ""
        }
    }
}

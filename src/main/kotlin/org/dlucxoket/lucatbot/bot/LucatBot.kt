package org.dlucxoket.lucatbot.bot

import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona
import org.dlucxoket.lucatbot.bot.persona.PersonaLoader
import org.dlucxoket.lucatbot.model.event.message.MessageEvent
import org.dlucxoket.lucatbot.model.session.ConversationId
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
        /** 回复的目标是机器人自己的消息（「回复我」= 冲我来的；唤醒资格同 @我） */
        val isReplyToMe: Boolean = false,
        val timestamp: Long = 0L,
        /** OneBot message_id（待插入注释渲染成「（#id）」，供 LLM 直接回复新消息） */
        val messageId: Long? = null,
        /** 图片/表情包原始地址（与 content 占位符同序，图片通道附件下载用） */
        val imageUrls: List<String>? = null
    )

    /**
     * 当前状态
     *
     * **跨线程共享**（S1 并发修复）：Shiro WS 消息线程（[handleMessage] 读）、
     * 主循环线程（enter/back/goIdle 写）、通知线程（读）三方并发——`@Volatile`
     * 保证写后可见，消除「主循环已切状态、消息线程仍读到旧值」的可见性问题。
     * 状态迁移本身由会话执行锁（MainLoopController.sessionGate）串行化，
     * 此处不做原子 CAS（迁移涉及多字段，CAS 会引入半更新状态）。
     */
    @Volatile
    var state: State = State.Idle

    /** 机器人人格（启动时从 persona.md 读取，见 [org.dlucxoket.lucatbot.bot.persona.PersonaLoader]） */
    val persona: BotPersona = PersonaLoader.persona

    /**
     * 当前心情
     *
     * **跨线程读写**（S1 并发修复）：多线程 `mood = mood.boost()` 式 read-modify-write
     * 既丢可见性也丢更新。`@Volatile` 解决可见性；丢更新请改用 [updateMood]
     * （原子读-改-写），两者并存是为了兼容旧调用点逐步迁移。
     */
    @Volatile
    var mood: BotMood = BotMood.guessByTime()

    /** mood 更新锁（[updateMood] 的原子读-改-写；不与 state 共锁，缩小临界区） */
    private val moodLock = Any()

    /**
     * 原子更新心情（**跨线程改 mood 的正确姿势**）——替代裸 `mood = mood.boost()`：
     * 两个线程同时 boost 时，裸写法有一个更新会被静默丢弃（读到同一旧值各写各的），
     * 本方法把「读-改-写」收进同一把锁，效果叠加。
     *
     * ```kotlin
     * lucatBot.updateMood { it.boost() }
     * ```
     */
    fun updateMood(transform: (BotMood) -> BotMood) {
        synchronized(moodLock) {
            mood = transform(mood)
        }
    }

    /** 未读消息队列（线程安全） */
    private val unreadQueue = java.util.concurrent.CopyOnWriteArrayList<UnreadMessage>()

    /**
     * 疑似被禁言的会话 → 最近一次禁言失败的时间（进程级，发送成功即清除）
     *
     * 发送失败 retCode 判禁言时打点；会话列表行渲染【疑似禁言 MM-dd】标记，
     * 让选会话 LLM 知情（日期=最近一次发现的更新时间，重复失败刷新）。
     */
    private val suspectedBans = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 记录/刷新疑似禁言（每次禁言失败刷新——标记日期即更新日期） */
    fun markSuspectedBan(conversationId: String) {
        suspectedBans[conversationId] = System.currentTimeMillis()
    }

    /** 发送成功 → 解除疑似 */
    fun clearSuspectedBan(conversationId: String) {
        suspectedBans.remove(conversationId)
    }

    /** 疑似禁言的最近发现时间（epoch ms；null = 无记录） */
    fun suspectedBanSince(conversationId: String): Long? = suspectedBans[conversationId]

    /**
     * 会话冷却——**刚退出的会话短时间内不再被选中**
     *
     * 实盘：群里对方只回了个表情包、无新内容可接 → return → 下一轮 pick 带着
     * 新未读又进同一个群 → 立刻又 return……几秒一轮反复横跳（action_log 里
     * 「进入群聊 / 返回消息列表」交替刷屏）。退出时打点，冷却期内该会话
     * **不进选会话候选**；冷却期满未读仍在自然会再进去。
     * 通知通道的「当场跳转」不走 pick，不受此限。
     */
    private val conversationLeftAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 退出会话时打点（[org.dlucxoket.lucatbot.bot.loop.ConversationController.run] 的 finally 里调用） */
    fun markConversationLeft(conversationId: String, at: Long = System.currentTimeMillis()) {
        conversationLeftAt[conversationId] = at
    }

    /** 该会话是否已过冷却（true = 可以被选会话选中） */
    fun conversationReady(conversationId: String, now: Long = System.currentTimeMillis()): Boolean {
        val left = conversationLeftAt[conversationId] ?: return true
        return now - left >= CONVERSATION_COOLDOWN_MS
    }

    /**
     * 冷却中的会话 → 剩余冷却毫秒（>0；没有冷却中的 → 空 map）
     *
     * 选会话把它们注入「### 刚退出（冷却中）」段，由 **prompt** 约束别马上再进
     * （不硬过滤——列表照样可见，@我/急事仍可由模型权衡后选）
     */
    fun recentlyLeft(now: Long = System.currentTimeMillis()): Map<String, Long> =
        conversationLeftAt.mapNotNull { (conv, at) ->
            val remain = CONVERSATION_COOLDOWN_MS - (now - at)
            if (remain > 0) conv to remain else null
        }.toMap()

    /**
     * 会话内要求**切换到别的会话**（[org.dlucxoket.lucatbot.bot.tool.SwitchConversationTool]
     * 置位 → ConversationController 结束当前会话 → run() 接着打开目标）
     */
    @Volatile
    private var pendingSwitch: SessionPick? = null

    /** 当前挂着的切换请求（peek；null = 无） */
    fun pendingConversationSwitch(): SessionPick? = pendingSwitch

    /** 记一次切换请求（type：private / group，非法值按 private 处理） */
    fun requestConversationSwitch(type: String, targetId: String) {
        pendingSwitch = SessionPick(
            conversationType = if (type.equals("group", ignoreCase = true)) {
                SessionPickType.GROUP
            } else {
                SessionPickType.PRIVATE
            },
            targetId = targetId
        )
    }

    /** 取走切换请求（一次性消费） */
    fun consumeConversationSwitch(): SessionPick? = pendingSwitch.also { pendingSwitch = null }

    companion object {
        /** 退出会话后的冷却时长：期间选会话不把该会话放进候选 */
        const val CONVERSATION_COOLDOWN_MS = 120_000L
    }

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

    /**
     * 会话让位请求（通知通道要当场跳转、而当前会话循环正占着执行锁）
     *
     * 置位 + 唤醒正在 STAY/延迟等待的会话循环 → 对方在**下一轮决策前的检查点
     * 让位退出**（记 todo「回到会话继续」后 return，被禁言的会话除外），
     * 释放执行锁给通知跳转。消费见 [consumeSessionYield]。
     */
    @Volatile
    private var yieldRequested = false

    /** 是否有未消费的让位请求（只读——open 类禁私有 setter，走私有字段+getter） */
    val sessionYieldRequested: Boolean
        get() = yieldRequested

    /** 请求当前会话让位（通知线程调用）：置位并唤醒会话内的等待 */
    fun requestSessionYield() {
        yieldRequested = true
        synchronized(pendingInsertLock) {
            pendingInsertLock.notifyAll()
        }
    }

    /** 消费让位请求（会话循环每轮检查点调用）：读取并清除 */
    fun consumeSessionYield(): Boolean {
        if (!yieldRequested) return false
        yieldRequested = false
        return true
    }

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
     * **通知/唤醒资格**（[hasWakeWorthyUnread] 同口径）：私聊、@我、@全体、
     * **回复我**——普通群聊消息只攒未读，不叫醒空闲的机器人。
     *
     * @param event 消息事件
     * @param isAtMe 是否 @了机器人
     * @param isAtAll 是否 @全体成员
     * @param isReplyToMe 是否在回复机器人自己的消息（MainPlugin 查档得出）
     * @return 处理动作
     */
    fun handleMessage(
        event: MessageEvent,
        isAtMe: Boolean,
        isAtAll: Boolean,
        isReplyToMe: Boolean = false
    ): MessageAction {
        val conversationId = event.conversationId

        return when (state) {
            // 没在管 QQ → 全部进未读，心情衰减
            is State.Idle -> {
                addUnread(event, conversationId, isAtMe, isAtAll, isReplyToMe)
                updateMood { it.decay() }
                MessageAction.IGNORE
            }

            // 在会话列表 → 看情况
            is State.ViewingConversations -> {
                if (isAtMe || isAtAll) {
                    updateMood { it.boost() }  // 有人找我，心情好一点
                    // 通知类消息同样入未读：通知通道判「无视」后，选会话侧
                    // 仍要能看到它（判「跳转」进会话时 consumeUnread 自动清）
                    addUnread(event, conversationId, isAtMe, isAtAll, isReplyToMe)
                    MessageAction.NOTIFY
                } else {
                    addUnread(event, conversationId, isAtMe, isAtAll, isReplyToMe)
                    updateMood { it.decay() }
                    MessageAction.IGNORE
                }
            }

            // 在某个会话中
            is State.InConversation -> {
                @Suppress("UNCHECKED_CAST")
                val current = state as State.InConversation
                if (conversationId == current.conversationId) {
                    // 当前会话的消息 → 进待插入队列，不打断决策循环
                    updateMood { it.boost() }
                    addPendingInsert(event, conversationId, isAtMe, isAtAll)
                    MessageAction.PROCESS
                } else if (isAtMe || isAtAll) {
                    // 其他会话但 @我/@全体 → 通知通道（同样入未读，无视可见/跳转即清）
                    updateMood { it.boost() }
                    addUnread(event, conversationId, isAtMe, isAtAll, isReplyToMe)
                    MessageAction.NOTIFY
                } else {
                    // 其他会话普通消息 → 未读（被冷落，心情微降）
                    addUnread(event, conversationId, isAtMe, isAtAll, isReplyToMe)
                    updateMood { it.drop() }
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
     * 某个会话是否有**回复机器人**的未读（会话行渲染「(@回复你)」标记用）
     */
    fun hasUnreadReplyToMe(conversationId: String): Boolean {
        return unreadQueue.any { it.conversationId == conversationId && it.isReplyToMe }
    }

    /**
     * 某个会话是否有**值得叫醒空闲机器人**的未读——
     * **私聊 / @我 / @全体 / 回复我** 四类冲我来的消息；
     * 普通群聊消息只攒未读，等主循环按心情自然醒来（ProactiveScheduler
     * 的 30s 巡查只认这里，`未读 ≥3 条就唤醒` 的旧规则已删）。
     */
    fun hasWakeWorthyUnread(conversationId: String): Boolean {
        return unreadQueue.any {
            it.conversationId == conversationId &&
                (it.isAtMe || it.isAtAll || it.isReplyToMe || it.conversationType == ConversationType.PRIVATE)
        }
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
     * 重启后灌回持久化的未读（ReadStateService 按已读水位重建）——
     * 仅当队列为空时写入，不覆盖运行中实时积累的未读
     */
    fun restoreUnread(messages: List<UnreadMessage>) {
        if (messages.isEmpty()) return
        if (unreadQueue.isNotEmpty()) {
            return  // 已有实时未读，重建结果丢弃（幂等重建本身由 Service 管）
        }
        unreadQueue.addAll(messages)
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
                // 让位请求也算唤醒（requestSessionYield 复用本等待；置位在 wait 前后都能醒）
                if (yieldRequested) return true
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
                    conversationType = if (ConversationId.parse(conversationId).isGroup) ConversationType.GROUP else ConversationType.PRIVATE,
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
    private fun addUnread(
        event: MessageEvent,
        conversationId: String,
        isAtMe: Boolean,
        isAtAll: Boolean,
        isReplyToMe: Boolean = false
    ) {
        unreadQueue.add(
            UnreadMessage(
                conversationId = conversationId,
                conversationType = if (ConversationId.parse(conversationId).isGroup) ConversationType.GROUP else ConversationType.PRIVATE,
                senderId = event.sender.qqNumber,
                senderName = event.sender.displayName,
                content = extractRichContent(event),
                isAtMe = isAtMe,
                isAtAll = isAtAll,
                isReplyToMe = isReplyToMe,
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
            // 与爬楼路径同口径截断：长文/合并转发若全量入队，会原样进未读与
            // 待插入注释、每轮决策重发（实时路径曾漏截，只有爬楼截了 800）
            org.dlucxoket.lucatbot.model.message.ChatMessage.truncateContent(msg.describe())
        } else {
            ""
        }
    }
}

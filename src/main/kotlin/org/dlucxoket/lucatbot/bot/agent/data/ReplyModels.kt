package org.dlucxoket.lucatbot.bot.agent.data

/**
 * 智能回复 Agent 的请求
 *
 * 模拟真人看到消息后产生回复意图时拥有的全部信息。
 * JSON 边界序列化 ↔ Agent 内部 OOP 操作。
 */
data class ReplyRequest(
    /** 当前会话的上下文描述 */
    val context: String,

    /** 要回复的消息内容 */
    val messages: List<ChatMessageItem> = emptyList(),

    /** 机器人的性格设定（影响说话风格） */
    val personality: String = "",

    /** 当前心情 */
    val mood: String = ""
)

/**
 * 聊天消息条目
 */
data class ChatMessageItem(
    val sender: String,
    val content: String,
    val timestamp: Long = 0L
)

/**
 * 智能回复 Agent 的响应
 *
 * 模拟真人的回复决策：说什么、要不要发、什么动作。
 */
data class ReplyResult(
    /** 回复内容 */
    val reply: String,

    /** 动作类型：send=发送、ignore=不回、emoji=发表情 */
    val action: String = "send",

    /** 置信度（0-1），用于调试 */
    val confidence: Double = 1.0
)

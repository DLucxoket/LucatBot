package org.dlucxoket.lucatbot.bot.chathistory

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 聊天记录条目
 *
 * **模拟真人的聊天记忆**：
 * - 记住每条消息的内容、发送者、时间
 * - 按会话组织（群聊/私聊）
 * - 支持查询历史记录
 *
 * 未读/已读不在持久化模型里：由 `LucatBot.unreadQueue` 维护。
 *
 * @param id 记录 ID
 * @param conversationId 会话 ID（如 "group:123" 或 "private:456"）
 * @param senderId 发送者 QQ 号
 * @param senderName 发送者展示名（[org.dlucxoket.lucatbot.model.user.User.displayName] 规则：群聊优先群名片）
 * @param senderNickname 发送者 QQ 昵称（展示名取了群名片时与 [senderName] 不同；供 prompt 注解区分"名片 vs 昵称"）
 * @param content 消息内容（含 [图片] 等标记）
 * @param messageType 消息类型（text/image/face 等）
 * @param timestamp 发送时间戳（毫秒）
 * @param isFromBot 是否是机器人自己发的
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatMessageRecord(
    val id: String = java.util.UUID.randomUUID().toString(),

    /** 会话 ID */
    val conversationId: String = "",

    /** 发送者 QQ 号 */
    val senderId: Long = 0L,

    /** 发送者展示名（群聊群名片 / 私聊 QQ 昵称，见 [formatSenderDisplay]） */
    val senderName: String = "",

    /**
     * 发送者 QQ 昵称——仅当展示名取了群名片时与 [senderName] 不同。
     *
     * 用于 prompt 注解「名片（QQ昵称 xx）」，避免 LLM 把群名片和 QQ 昵称
     * 当成两个人；私聊无名片，两者相同（注解自动省略）。旧数据无此字段。
     */
    val senderNickname: String? = null,

    /** 消息内容 */
    val content: String = "",

    /** 消息类型 */
    val messageType: MessageType = MessageType.TEXT,

    /**
     * 消息里图片/表情包的原始地址（**图片通道**）——按成员顺序收集，
     * 与 content 里 `[图片 file=x]` 占位符序列按下标对应。
     *
     * prompt 时由 ImageResolver 下载成 data URI 附件给 LLM 看图；
     * 旧数据无此字段（占位符文本仍在，只是没有附件）。base64/空白地址不收。
     */
    val imageUrls: List<String>? = null,

    /** 发送时间戳（毫秒） */
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * OneBot message_id（被引用解析的键：`回复 #id` 靠它定位到具体消息）
     *
     * 来源：实时事件、发送回执（`MsgId` 对象，非裸数字）、爬楼自定义 DTO
     * 三条路径；带 id 一律用稳定 id `msg:<会话>:<id>`（同一物理消息同主键合一）。
     * 缺失为 null（如 Shiro 类型化回退路径），此时 id 为 `hist:`/UUID 兜底，
     * 由读取折叠与启动清理收敛。旧数据无此字段。
     */
    val messageId: Long? = null,

    /** 是否是机器人发的 */
    val isFromBot: Boolean = false,

    /**
     * 这条消息**已入长期记忆**——记忆整理/提取器以它为来源写过记忆后打标。
     *
     * 打标后注入记忆整理 prompt 的行尾带「（已记忆）」：整理智能体看到标记
     * 就不再为这条消息产出记忆——防「退出整理把同一段对话改写一遍又记一遍」
     * （写入判重是精确匹配，改写式重复拦不住，靠这个标记在源头堵）。
     */
    val memorized: Boolean = false
) {
    /** 消息内容类型：纯文本 / 图片 / 表情 / 表情包 / @ / 混合 */
    enum class MessageType {
        TEXT,
        IMAGE,
        FACE,
        MEME,
        AT,
        POKE,
        MIXED
    }
}

/**
 * prompt 里的发送者显示串——**名片 vs QQ昵称 的注解规则**
 *
 * 展示名与 QQ 昵称不同（群名片场景）时输出 `名片（QQ昵称 xx）`，
 * LLM 才知道这是同一个 QQ 号的两个名字，不会当成两个人；
 * 相同（私聊/无名片/旧数据）时原样输出，不加冗余。
 *
 * 网友随时可能改群名片或改 QQ 名——改名后展示串会跟着变，但
 * [ChatMessageRecord.senderId]（QQ 号）不变，身份判定以 QQ 号为准。
 */
fun formatSenderDisplay(name: String, nickname: String?): String =
    if (!nickname.isNullOrBlank() && nickname != name) "$name（QQ昵称 $nickname）" else name

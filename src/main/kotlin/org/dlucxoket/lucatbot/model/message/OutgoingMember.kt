package org.dlucxoket.lucatbot.model.message

/**
 * 结构化消息段（send 分段发送的 `members` 成员——LLM JSON → OOP 的扁平 DTO）
 *
 * send 的 `params.content` 是 JSON 数组（每元素一条独立消息），条内
 * `members` 数组的每个对象映射到本 DTO，由
 * [org.dlucxoket.lucatbot.bot.tool.SendMessageTool] 转成 OneBot 消息段：
 *
 * - `type = "text"` → [text] 文字段（仍过富标记解析：@昵称(QQ) / [表情:14] / 回复 #id：）
 * - `type = "at"`   → [qq] 艾特（QQ 号字符串，或 `"all"` = @全体成员）
 * - `type = "face"` → [id] QQ 内置表情（数字 id）
 * - 未知 type 丢弃（图片/表情包没有发送通道）
 *
 * 扁平 + 全可空字段：Jackson 反序列化无多态负担（[org.dlucxoket.lucatbot.bot.tool.data.SendMessageRequest]
 * 的 JSON 路径安全）；字段类型统一 String，边界处自行容错转换。
 */
data class OutgoingMember(
    /** 段类型：text / at / face（小写比较） */
    val type: String? = null,
    /** type=text 时的正文 */
    val text: String? = null,
    /** type=at 时的目标：QQ 号字符串或 "all" */
    val qq: String? = null,
    /** type=face 时的表情 id */
    val id: String? = null
)

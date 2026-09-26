package org.dlucxoket.lucatbot.adapter.onebot

import com.mikuac.shiro.dto.event.message.AnyMessageEvent
import com.mikuac.shiro.dto.message.MessageSegment
import org.dlucxoket.lucatbot.model.event.message.FriendMessageEvent
import org.dlucxoket.lucatbot.model.event.message.GroupMessageEvent
import org.dlucxoket.lucatbot.model.event.message.MessageEvent
import org.dlucxoket.lucatbot.model.event.message.PrivateMessageEvent
import org.dlucxoket.lucatbot.model.group.Group
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.Message
import org.dlucxoket.lucatbot.model.message.member.AtMessageMember
import org.dlucxoket.lucatbot.model.message.member.FaceMessageMember
import org.dlucxoket.lucatbot.model.message.member.ImageMessageMember
import org.dlucxoket.lucatbot.model.message.member.MemeMessageMember
import org.dlucxoket.lucatbot.model.message.member.MessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.dlucxoket.lucatbot.model.user.Sex
import org.dlucxoket.lucatbot.model.user.User

/**
 * Shiro DTO → model 层对象转换器
 *
 * 将 OneBot/Shiro 的事件和消息结构映射为业务层自定义 model，
 * 使核心逻辑不依赖 Shiro 框架类。
 */
object ShiroConverter {

    /**
     * 将 Shiro 的 AnyMessageEvent 转为 model 层的 MessageEvent
     *
     * 根据消息来源（私聊/群聊）自动选择对应的 MessageEvent 子类。
     *
     * @param event Shiro 消息事件
     * @return 对应的 model 层 MessageEvent
     */
    fun toModelEvent(event: AnyMessageEvent): MessageEvent {
        val message = toModelMessage(event.message)
        val sender = toModelUser(event)
        val timestamp = event.time * 1000L  // OneBot 秒级时间戳 → 毫秒

        return if (event.groupId != null && event.groupId > 0) {
            // 群消息
            val group = Group(
                groupNumber = event.groupId,
                groupName = "" // 群名需额外调 API 获取，这里先留空
            )
            GroupMessageEvent(message, sender, group, timestamp)
        } else {
            // 私聊消息（默认按好友处理）
            FriendMessageEvent(message, sender, timestamp)
        }
    }

    /**
     * 将 Shiro 的 AnyMessage 转为 model 层的 ChatMessage
     *
     * 遍历消息段列表，按类型映射为对应的 MessageMember。
     *
     * @param message Shiro 消息对象
     * @return model 层 ChatMessage
     */
    fun toModelMessage(message: com.mikuac.shiro.dto.message.AnyMessage): ChatMessage {
        val members = message.messageList.mapNotNull { segment ->
            toModelMember(segment)
        }
        return ChatMessage(members)
    }

    /**
     * 将单个 Shiro MessageSegment 转为 model 层的 MessageMember
     *
     * 支持的片段类型：text、at、image、face、meme。
     * 未识别的类型返回 null（跳过）。
     *
     * @param segment Shiro 消息段
     * @return 对应的 MessageMember，不支持时返回 null
     */
    private fun toModelMember(segment: MessageSegment): MessageMember? {
        return when (segment.type) {
            "text" -> TextMessageMember(segment.data["text"] ?: "")
            "at" -> AtMessageMember(segment.data["qq"]?.toLongOrNull() ?: 0L)
            "image" -> ImageMessageMember(segment.data["url"] ?: segment.data["file"] ?: "")
            "face" -> FaceMessageMember(segment.data["id"]?.toIntOrNull() ?: 0)
            "meme", "sface" -> MemeMessageMember(segment.data["url"] ?: segment.data["file"] ?: "")
            else -> null
        }
    }

    /**
     * 从 Shiro 事件中提取发送者信息，转为 model 层 User
     *
     * @param event Shiro 消息事件
     * @return model 层 User
     */
    private fun toModelUser(event: AnyMessageEvent): User {
        val sender = event.sender
        return User(
            qqNumber = sender.userId,
            nickname = sender.nickname ?: "",
            sex = when (sender.sex) {
                "male" -> Sex.MALE
                "female" -> Sex.FEMALE
                else -> Sex.UNKNOWN
            },
            level = 0, // Shiro Sender 无等级字段
            signature = "" // 需额外调 API 获取
        )
    }

    /**
     * 提取消息的纯文本内容（拼接所有文本片段）
     *
     * 用于 Agent 链路的 initialInput。
     *
     * @param message Shiro 消息对象
     * @return 拼接后的纯文本
     */
    fun extractPlainText(message: com.mikuac.shiro.dto.message.AnyMessage): String {
        return message.messageList
            .filter { it.type == "text" }
            .joinToString("") { it.data["text"] ?: "" }
            .trim()
    }
}

package org.dlucxoket.lucatbot.adapter.onebot

import com.mikuac.shiro.dto.event.message.AnyMessageEvent
import com.mikuac.shiro.enums.MsgTypeEnum
import com.mikuac.shiro.model.ArrayMsg
import org.dlucxoket.lucatbot.model.event.message.FriendMessageEvent
import org.dlucxoket.lucatbot.model.event.message.GroupMessageEvent
import org.dlucxoket.lucatbot.model.event.message.MessageEvent
import org.dlucxoket.lucatbot.model.group.Group
import org.dlucxoket.lucatbot.model.message.ChatMessage
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
        val message = toModelMessage(event)
        val sender = toModelUser(event)
        val timestamp = event.time * 1000L  // OneBot 秒级时间戳 → 毫秒

        return if (event.groupId != null && event.groupId > 0) {
            val group = Group(
                groupNumber = event.groupId,
                groupName = ""
            )
            GroupMessageEvent(message, sender, group, timestamp)
        } else {
            FriendMessageEvent(message, sender, timestamp)
        }
    }

    /**
     * 将 Shiro 事件中的消息段转为 model 层的 ChatMessage
     *
     * @param event Shiro 消息事件
     * @return model 层 ChatMessage
     */
    fun toModelMessage(event: AnyMessageEvent): ChatMessage {
        val segments = event.arrayMsg ?: return ChatMessage(emptyList())
        val members = segments.mapNotNull { segment ->
            toModelMember(segment)
        }
        return ChatMessage(members)
    }

    /**
     * 将单个 Shiro ArrayMsg 段转为 model 层的 MessageMember
     *
     * 支持的片段类型：text、at、image、face、meme。
     * 未识别的类型返回 null（跳过）。
     *
     * @param segment Shiro 消息段
     * @return 对应的 MessageMember，不支持时返回 null
     */
    private fun toModelMember(segment: ArrayMsg): MessageMember? {
        return when (segment.type) {
            MsgTypeEnum.text -> TextMessageMember(segment.getStringData("text") ?: "")
            MsgTypeEnum.at -> AtMessageMember(segment.getStringData("qq")?.toLongOrNull() ?: 0L)
            MsgTypeEnum.image -> ImageMessageMember(segment.getStringData("url") ?: segment.getStringData("file") ?: "")
            MsgTypeEnum.face -> FaceMessageMember(segment.getStringData("id")?.toIntOrNull() ?: 0)
            MsgTypeEnum.mface -> MemeMessageMember(segment.getStringData("url") ?: segment.getStringData("file") ?: "")
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
            qqNumber = event.userId ?: 0L,
            nickname = sender?.nickname ?: "",
            sex = when (sender?.sex) {
                "male" -> Sex.MALE
                "female" -> Sex.FEMALE
                else -> Sex.UNKNOWN
            },
            level = 0,
            signature = ""
        )
    }

    /**
     * 提取消息的纯文本内容（拼接所有文本片段）
     *
     * 用于 Agent 链路的 initialInput。
     *
     * @param event Shiro 消息事件
     * @return 拼接后的纯文本
     */
    fun extractPlainText(event: AnyMessageEvent): String {
        val segments = event.arrayMsg ?: return event.message ?: ""
        return segments
            .filter { it.type == MsgTypeEnum.text }
            .joinToString("") { it.getStringData("text") ?: "" }
            .trim()
    }
}

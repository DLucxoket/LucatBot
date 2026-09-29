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
import org.dlucxoket.lucatbot.model.message.member.ReplyMessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.dlucxoket.lucatbot.model.user.Sex
import org.dlucxoket.lucatbot.model.user.User

/**
 * Shiro DTO → model 层对象转换器
 *
 * 将 OneBot/Shiro 的事件和消息结构映射为业务层自定义 model，
 * 使核心逻辑不依赖 Shiro 框架类。
 *
 * 入档 / 未读 / 注入 LLM 请优先用 [richContent]（或 ChatMessage.describe()），
 * [extractPlainText] 仅保留兼容，会丢掉 @/图片/表情/reply。
 */
object ShiroConverter {

    /** CQ 码正则：`[CQ:type,k=v,...]`（值内的逗号在协议层已转义为 &#44;） */
    private val cqCodeRegex = Regex("""\[CQ:([a-zA-Z_0-9]+),([^\]]*)]""")

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
     * 支持的片段类型：text、at、image、face、mface、reply。
     * 未识别的类型返回 null（跳过）。
     *
     * @param segment Shiro 消息段
     * @return 对应的 MessageMember，不支持时返回 null
     */
    private fun toModelMember(segment: ArrayMsg): MessageMember? {
        return when (segment.type) {
            MsgTypeEnum.text -> TextMessageMember(segment.getStringData("text") ?: "")
            MsgTypeEnum.at -> {
                val qq = segment.getStringData("qq")?.trim()?.trim('"')?.toLongOrNull() ?: 0L
                val name = firstNotBlank(
                    segment.getStringData("card"),
                    segment.getStringData("nickname"),
                    segment.getStringData("name")
                )
                AtMessageMember(qq, name)
            }
            MsgTypeEnum.image -> {
                val file = firstNotBlank(segment.getStringData("file"))
                val url = firstNotBlank(segment.getStringData("url"), file).orEmpty()
                ImageMessageMember(url, file)
            }
            MsgTypeEnum.face -> FaceMessageMember(segment.getStringData("id")?.trim()?.trim('"')?.toIntOrNull() ?: 0)
            MsgTypeEnum.mface, MsgTypeEnum.marketface -> {
                val file = firstNotBlank(segment.getStringData("file"))
                val url = firstNotBlank(segment.getStringData("url"), file).orEmpty()
                MemeMessageMember(url, file)
            }
            MsgTypeEnum.reply -> {
                val id = segment.getStringData("id")?.trim()?.trim('"')?.toLongOrNull() ?: 0L
                val name = firstNotBlank(segment.getStringData("text")?.trim()?.trim('"'))
                ReplyMessageMember(id, name)
            }
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
     * 消息的富内容描述（入档 / 未读 / prompt 注入推荐入口）
     *
     * 与 [ChatMessage.describe] 同风格：@ 带 QQ/昵称、reply 带回复目标、
     * 图片/表情/表情包带占位符，禁止 base64。
     *
     * @param event Shiro 消息事件
     * @return 富内容字符串；解析不出任何段时尝试回退到 message 字段
     */
    fun richContent(event: AnyMessageEvent): String {
        val chat = toModelMessage(event)
        if (chat.messageMembers.isNotEmpty()) {
            return chat.describe()
        }
        return describeRawMessage(event.message ?: "")
    }

    /**
     * 提取消息的纯文本内容（拼接所有文本片段）
     *
     * **兼容保留**：会丢掉 @/图片/表情/reply，入档与 LLM 注入请改用 [richContent]。
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

    /**
     * 把 OneBot CQ 码字符串（爬楼远端 message 字段）转为与 describe 同风格的富文本
     *
     * 解析失败或纯文本时尽量保留原文；禁止把大段 base64 写入。
     */
    fun describeRawMessage(raw: String): String {
        if (raw.isBlank()) return ""
        if (!raw.contains("[CQ:")) {
            return raw
        }
        return try {
            val described = ChatMessage(cqToModelMembers(raw)).describe()
            if (described.isNotBlank()) described else raw
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * CQ 码字符串 → model MessageMember 列表（保持段顺序）
     */
    fun cqToModelMembers(raw: String): List<MessageMember> {
        val members = mutableListOf<MessageMember>()
        if (raw.isEmpty()) return members
        var last = 0
        for (match in cqCodeRegex.findAll(raw)) {
            val before = raw.substring(last, match.range.first)
            if (before.isNotEmpty()) {
                members += TextMessageMember(before)
            }
            val type = match.groupValues[1].lowercase()
            val params = parseCqParams(match.groupValues[2])
            when (type) {
                "text" -> params["text"]?.let { if (it.isNotEmpty()) members += TextMessageMember(it) }
                "at" -> {
                    val qq = params["qq"]?.toLongOrNull() ?: 0L
                    val name = firstNotBlank(params["card"], params["nickname"], params["name"])
                    members += AtMessageMember(qq, name)
                }
                "image" -> {
                    val file = firstNotBlank(params["file"])
                    val url = firstNotBlank(params["url"], file).orEmpty()
                    val subType = params["sub_type"] ?: ""
                    val summary = params["summary"] ?: ""
                    val isMemeLike = subType == "1" || summary.contains("表情")
                    if (isMemeLike) {
                        members += MemeMessageMember(url, file)
                    } else {
                        members += ImageMessageMember(url, file)
                    }
                }
                "mface", "marketface" -> {
                    val file = firstNotBlank(params["file"])
                    val url = firstNotBlank(params["url"], file).orEmpty()
                    members += MemeMessageMember(url, file)
                }
                "face" -> members += FaceMessageMember(params["id"]?.toIntOrNull() ?: 0)
                "reply" -> {
                    val id = params["id"]?.toLongOrNull() ?: 0L
                    members += ReplyMessageMember(id, firstNotBlank(params["text"]))
                }
                // 其余段（forward/poke 等）不进 describe 正文，避免污染
                else -> Unit
            }
            last = match.range.last + 1
        }
        val tail = raw.substring(last)
        if (tail.isNotEmpty()) {
            members += TextMessageMember(tail)
        }
        return members
    }

    /** 解析 CQ 段参数；处理 &#44; 等转义 */
    private fun parseCqParams(paramStr: String): Map<String, String> {
        if (paramStr.isBlank()) return emptyMap()
        return paramStr.split(',')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = part.substring(0, idx).trim()
                val value = part.substring(idx + 1)
                    .replace("&amp;", "&")
                    .replace("&#44;", ",")
                    .replace("&quot;", "\"")
                    .replace("&#91;", "[")
                    .replace("&#93;", "]")
                key to value
            }
            .toMap()
    }

    private fun firstNotBlank(vararg values: String?): String? {
        return values
            .mapNotNull { v ->
                v?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "\"\"" }
            }
            .firstOrNull()
    }
}

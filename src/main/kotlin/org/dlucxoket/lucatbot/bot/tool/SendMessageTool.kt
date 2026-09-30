package org.dlucxoket.lucatbot.bot.tool

import com.mikuac.shiro.enums.MsgTypeEnum
import com.mikuac.shiro.model.ArrayMsg
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.model.message.SendContentParser
import org.dlucxoket.lucatbot.model.message.member.AtMessageMember
import org.dlucxoket.lucatbot.model.message.member.FaceMessageMember
import org.dlucxoket.lucatbot.model.message.member.MessageMember
import org.dlucxoket.lucatbot.model.message.member.ReplyMessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember

/**
 * 发送消息工具——**支持富消息**（回复 / @某人 / @全体 / QQ 表情）
 *
 * content 用与聊天记录完全相同的 describe 风格标记（LLM 照抄历史即可），
 * 经 [SendContentParser] 解析成消息段，再转 OneBot 数组消息发送——
 * 数组模式文本原样入段（不像 CQ 字符串要转义，`[CQ:` 字样也不会被二次解析）。
 *
 * 发送成功后**必须写入聊天记录**（isBot=true，content 存标记原文，供后续
 * 回复引用与 prompt 圆环一致），避免 LLM 看到失忆上下文；失败不写档。
 */
object SendMessageTool : Tool<SendMessageRequest, SendMessageResult>() {
    override val name: String = "SendMessageTool"

    override val description: String = "功能：向指定会话发送消息（content 支持富标记：回复 #消息id：/ @昵称(QQ号) / @全体成员 / [表情:数字]，会转成真实的引用回复、艾特与 QQ 表情），发送成功后自动写入聊天记录（isBot=true），发送失败（如被禁言）不写档并返回错误详情。\n何时使用：会话内的 send 动作由编排层自动调用本工具发送，并同步写短期记忆、记操作日志，常规回复无需手动调用；仅当在编排层未覆盖的场景需要补发消息时才调用，避免与 send 动作重复发送。"

    override val parameterHint: String = "JSON: {\"conversationId\": \"private:123456\", \"content\": \"回复 #581866777：我也是 @小明(10001) [表情:14]\"}"

    override val inputClass: Class<SendMessageRequest> = SendMessageRequest::class.java

    /** 聊天记录服务（发送成功后写入自身发言；启动时经 [bindServices] 注入） */
    @Volatile
    private var chatHistoryService: ChatHistoryService? = null

    override fun bindServices(services: ToolServices) {
        chatHistoryService = services.chatHistoryService
    }

    override fun handle(input: SendMessageRequest): SendMessageResult {
        val b = bot ?: return SendMessageResult(success = false, error = "Bot 未绑定")

        return try {
            val parts = input.conversationId.split(":")
            if (parts.size != 2) {
                return SendMessageResult(success = false, error = "会话ID格式错误: ${input.conversationId}")
            }
            val type = parts[0]
            val id = parts[1].toLongOrNull() ?: return SendMessageResult(success = false, error = "无效ID")

            // 富标记 → 消息段：结构化（reply+members，分段发送的单条）优先；
            // 否则旧 content 走富标记解析。解析为空（空内容或全是丢弃型标记）按失败返回
            val segments = if (input.members.isNotEmpty() || input.replyToId != null) {
                buildSegments(input.replyToId, input.members)
            } else {
                toWire(SendContentParser.parse(input.content))
            }
            if (segments.isEmpty()) {
                return SendMessageResult(success = false, error = "消息内容为空（或只含无法发送的标记，如 [图片]）")
            }

            val resp = when (type) {
                "group" -> b.sendGroupMsg(id, segments, false)
                "private" -> b.sendPrivateMsg(id, segments, false)
                else -> return SendMessageResult(success = false, error = "未知会话类型: $type")
            }

            // OneBot 返回非 ok（被禁言时 NapCat 返回 status=failed/retcode=120，Shiro 已打 ERROR 日志）
            // ——消息实际没发出去：不写聊天记录，直接返回失败，由调用方通知 LLM 决定去留
            if (resp?.status != "ok") {
                return SendMessageResult(
                    success = false,
                    error = "发送失败 status=${resp?.status} retCode=${resp?.retCode}（可能被禁言）"
                )
            }

            // 发出后必须写入聊天记录（isBot=true）；回执里的 message_id 一并入档，
            // 网友回复这条消息时引用解析（回复 #id）才能定位到它。
            // 回执 data 是 MsgId 对象（ActionData<MsgId>），不是裸数字——
            // 曾用 `as? Number` 恒为 null，机器人消息全部丢失 id（UUID 兜底行）
            val sentMessageId = (resp?.data as? com.mikuac.shiro.dto.action.common.MsgId)?.messageId?.toLong()
                ?: (resp?.data as? Number)?.toLong()
            // content 存标记原文（与聊天记录同风格）：后续回复引用、prompt 圆环一致
            chatHistoryService?.recordBotReply(input.conversationId, input.content, sentMessageId)

            SendMessageResult(success = true)
        } catch (e: Exception) {
            SendMessageResult(success = false, error = e.message)
        }
    }

    /**
     * 结构化消息段 → OneBot 数组消息（reply 段统一提到头部——与富标记路径同规则）
     *
     * - [replyToId] → 引用段（一条消息最多一个回复）
     * - `text` 仍过 [SendContentParser]（老标记 @昵称(QQ) / [表情:14] / 回复 #id：
     *   兼容生效）；顶层已有 reply 时丢弃 text 内解析出的回复段，保证不重复
     * - `at`：qq=`all` → @全体成员，否则按 QQ 号；`face`：数字表情 id
     * - 未知 type 丢弃（图片/表情包没有发送通道）
     */
    internal fun buildSegments(
        replyToId: Long?,
        members: List<org.dlucxoket.lucatbot.model.message.OutgoingMember>
    ): List<ArrayMsg> {
        val out = mutableListOf<ArrayMsg>()
        replyToId?.let {
            out += ArrayMsg().setType(MsgTypeEnum.reply).setData(mapOf("id" to it.toString()))
        }
        for (m in members) {
            when (m.type) {
                "text" -> {
                    val parsed = SendContentParser.parse(m.text.orEmpty())
                        .filter { replyToId == null || it !is ReplyMessageMember }
                    out += toWire(parsed)
                }
                "at" -> {
                    val qq = m.qq.orEmpty()
                    if (qq.isNotEmpty()) {
                        out += ArrayMsg().setType(MsgTypeEnum.at)
                            .setData(mapOf("qq" to if (qq.equals("all", ignoreCase = true)) "all" else qq))
                    }
                }
                "face" -> m.id?.toIntOrNull()?.let { fid ->
                    out += ArrayMsg().setType(MsgTypeEnum.face).setData(mapOf("id" to fid.toString()))
                }
                else -> Unit  // 未知类型丢弃（图片/表情包无发送通道）
            }
        }
        return out
    }

    /**
     * 消息段 → OneBot 数组消息（Shiro [ArrayMsg]）
     *
     * data 值统一字符串（OneBot v11 规范）；`type` 用 [MsgTypeEnum] 常量名
     * （本身就是小写，序列化即 `text/at/face/reply`）。
     * 无法按引用重发的段（图片/表情包）防御性丢弃——解析器已过滤。
     */
    fun toWire(members: List<MessageMember>): List<ArrayMsg> = members.mapNotNull { m ->
        when (m) {
            is TextMessageMember ->
                ArrayMsg().setType(MsgTypeEnum.text).setData(mapOf("text" to m.text))

            is AtMessageMember ->
                ArrayMsg().setType(MsgTypeEnum.at)
                    .setData(mapOf("qq" to if (m.isAtAll()) "all" else m.targetQqNumber.toString()))

            is ReplyMessageMember ->
                ArrayMsg().setType(MsgTypeEnum.reply).setData(mapOf("id" to m.replyToId.toString()))

            is FaceMessageMember ->
                ArrayMsg().setType(MsgTypeEnum.face).setData(mapOf("id" to m.faceId.toString()))

            else -> null
        }
    }
}

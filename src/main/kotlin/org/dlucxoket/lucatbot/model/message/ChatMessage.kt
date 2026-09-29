package org.dlucxoket.lucatbot.model.message

import org.dlucxoket.lucatbot.model.message.member.AtMessageMember
import org.dlucxoket.lucatbot.model.message.member.FaceMessageMember
import org.dlucxoket.lucatbot.model.message.member.ImageMessageMember
import org.dlucxoket.lucatbot.model.message.member.MemeMessageMember
import org.dlucxoket.lucatbot.model.message.member.MessageMember
import org.dlucxoket.lucatbot.model.message.member.ReplyMessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember

/**
 * 对话消息（ChatMessage）
 *
 * 表示一条包含若干消息片段（[MessageMember]）的复合消息。
 * 例如一条 QQ 消息可能是 "文字 + @某人 + 图片"，每个部分是一个 MessageMember。
 *
 * @param messageMembers 消息成员列表，按顺序排列
 */
open class ChatMessage(
    val messageMembers: List<MessageMember>
) : org.dlucxoket.lucatbot.model.message.Message {

    /**
     * 是否 @全体成员
     */
    fun isAtAll(): Boolean {
        return messageMembers.any {
            it is AtMessageMember && it.isAtAll()
        }
    }

    /**
     * 是否含有非纯文本片段（@/reply/图片/表情/表情包等）
     */
    fun hasNonTextContent(): Boolean {
        return messageMembers.any { it !is TextMessageMember }
    }

    /**
     * 提取纯文本内容（只取文本片段）
     *
     * 仅适合「只要说话文字」的场景；注入 LLM / 入档请用 [describe]。
     */
    fun extractText(): String {
        return messageMembers
            .filterIsInstance<TextMessageMember>()
            .joinToString("") { it.text }
    }

    /**
     * 自然语言描述整条消息（含非文本内容）——**注入 LLM / 入档的唯一推荐 API**
     *
     * **模拟真人感知消息的方式**：
     * - 纯文本 → 原文
     * - @ → `@昵称(QQ)`（无昵称则 `@QQ`）；@全体 → `@全体成员`
     * - reply → `回复 #消息id：` 或 `回复 @昵称：`
     * - 图片 → `[图片 file=xxx.jpg]` / `[图片]`（禁止输出 base64）
     * - QQ 表情 → `[表情:id]`
     * - 表情包 → `[表情包 file=xxx]` / `[表情包]`
     *
     * 按原始段顺序拼接，混合消息不丢段。
     *
     * @param targetNameResolver 可选：把 QQ 号映射成昵称（如群成员表）；成员自带昵称时优先用成员字段
     */
    fun describe(targetNameResolver: ((Long) -> String?)? = null): String {
        val parts = messageMembers.map { member ->
            when (member) {
                is TextMessageMember -> member.text
                is AtMessageMember -> {
                    val name = member.targetName
                        ?.takeIf { it.isNotBlank() }
                        ?: targetNameResolver?.invoke(member.targetQqNumber)?.takeIf { it.isNotBlank() }
                    val target = if (name != null) "$name(${member.targetQqNumber})" else "${member.targetQqNumber}"
                    // 尾部空格：@ 后接正文时保持「@小明(123456) 这个你行吗」
                    if (member.isAtAll()) "@全体成员 " else "@$target "
                }
                is ReplyMessageMember -> {
                    val name = member.replyToName?.takeIf { it.isNotBlank() }
                    if (name != null) "回复 @${name}：" else "回复 #${member.replyToId}："
                }
                is ImageMessageMember -> "[图片${shortAssetRef(member.fileName, member.url)}]"
                is FaceMessageMember -> "[表情:${member.faceId}]"
                is MemeMessageMember -> "[表情包${shortAssetRef(member.fileName, member.url)}]"
                else -> "[消息]"
            }
        }
        return joinDescribed(parts)
    }

    /**
     * 拼接各段：保持顺序；@/回复/图片/表情包 前必要时空格；QQ 表情可紧贴正文
     */
    private fun joinDescribed(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        val sb = StringBuilder()
        for (part in parts) {
            if (sb.isEmpty()) {
                sb.append(part)
                continue
            }
            val prev = sb.last()
            val partStartsMarker = part.startsWith("@") || part.startsWith("回复") ||
                part.startsWith("[图片") || part.startsWith("[表情包")
            if (partStartsMarker && prev != ' ' && prev != '：' && prev != ':') {
                sb.append(' ')
            }
            // 前一段已以空格结尾（如 @尾空格）时，去掉本段前导空格，避免双空格
            val toAppend = if (prev == ' ' && part.startsWith(' ')) part.trimStart(' ') else part
            sb.append(toAppend)
        }
        return sb.toString().trim()
    }

    /**
     * 图片/表情包的原始地址（**图片通道的成员侧**）
     *
     * 按成员顺序收集（与 [describe] 的占位符顺序天然对齐），供入档存进
     * `ChatMessageRecord.imageUrls`——prompt 时按下标与占位符对应、下载成
     * data URI 附件。过滤 base64/空白（describe 同样拒绝展示它们）。
     */
    fun assetUrls(): List<String> = messageMembers.mapNotNull { member ->
        val url = when (member) {
            is ImageMessageMember -> member.url
            is MemeMessageMember -> member.url
            else -> null
        }
        url?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("base64") }
    }

    /**
     * 图片/表情包的可辨识短标识：优先文件名；其次 URL 文件名；禁止 base64 全量
     */
    private fun shortAssetRef(fileName: String?, url: String?): String {
        val name = fileName?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("base64") }
        if (name != null) return " file=$name"

        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("base64://") || raw.startsWith("base64,")) return ""

        val fileFromUrl = raw.substringAfterLast('/').substringBefore('?')
        return when {
            fileFromUrl.isNotBlank() && fileFromUrl.length <= 60 && !raw.startsWith("http") ->
                " file=$fileFromUrl"
            raw.startsWith("http") && raw.length <= 120 && fileFromUrl.isNotBlank() && fileFromUrl.length <= 60 ->
                " url=$fileFromUrl"
            raw.length <= 60 && !raw.startsWith("http") -> " file=$raw"
            raw.startsWith("http") && raw.length <= 80 -> " url=$raw"
            else -> ""
        }
    }
}

package org.dlucxoket.lucatbot.model.message

import org.dlucxoket.lucatbot.model.message.member.AtMessageMember
import org.dlucxoket.lucatbot.model.message.member.FaceMessageMember
import org.dlucxoket.lucatbot.model.message.member.MessageMember
import org.dlucxoket.lucatbot.model.message.member.ReplyMessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember

/**
 * send 动作 content 的富消息解析——`ChatMessage.describe()` 的**逆变换**
 *
 * LLM 在聊天记录里看到的是 describe 风格标记，send 时用**完全相同的标记**表达
 * 富消息（零新语法，照抄历史即可），本对象把它们还原成消息段：
 *
 * | 标记 | 消息段 | 说明 |
 * |------|--------|------|
 * | `回复 #581866777：` | [ReplyMessageMember] | id 取聊天记录行上的（#数字）；解析后统一提到消息头部（OneBot 约定引用段在最前，无论 LLM 把标记写在哪） |
 * | `@昵称(QQ号)` / `@123456789` | [AtMessageMember] | 名字部分随意（含「寄（QQ昵称 XENO）」式注解），实际艾特只用 QQ 号 |
 * | `@全体成员` | [AtMessageMember]（isAtAll） | 协议层转 `qq=all` |
 * | `[表情:14]` / `[表情：14]` | [FaceMessageMember] | 数字是 QQ 表情 id |
 * | `[图片 …]` / `[表情包 …]` / `[表情]` | **丢弃** | 只有占位符没有文件，无法按引用重发（prompt 已声明发不了） |
 * | `（#123456）` | **丢弃** | 聊天记录行的消息 id 标记，只用于回复引用，不该出现在正文里 |
 *
 * 纯文本按原顺序保留（整体 trim 首尾；丢弃型标记切碎的相邻文本会重新合并）；
 * 解析结果为空 = 消息发不出去（调用方按失败返回）。
 */
object SendContentParser {

    /**
     * 富标记（按 find 顺序扫描，段间文本为纯文本）：
     * 回复 / @全体成员 / @名字(QQ) / @裸QQ / 表情 / 无法重发的占位符 / 行 id 标记
     *
     * @名字(QQ) 的名字允许空格与全角括号（展示名可能带「（QQ昵称 xx）」注解），
     * 但排除 ASCII 括号——防止 `@小明(明天)…(123456)` 跨括号误吞。
     */
    private val markerRegex = Regex(
        """回复\s*#(\d+)\s*[:：]?\s*""" +
            """|@全体成员""" +
            """|@([^\n()]*?)\s*\((\d{4,})\)""" +
            """|@(\d{4,})""" +
            """|\[表情[:：](\d+)]""" +
            """|\[表情]""" +
            """|\[图片[^\]]*]""" +
            """|\[表情包[^\]]*]""" +
            """|（#\d+）"""
    )

    /**
     * 解析 send content 为消息段序列
     *
     * 段间文本按原顺序保留；reply 段统一提到最前（OneBot/NapCat 约定
     * 引用段在消息头部，无论 LLM 把标记写在哪）；丢弃型标记切碎的
     * 相邻文本段重新合并。
     */
    fun parse(content: String): List<MessageMember> {
        val source = content.trim()
        if (source.isEmpty()) return emptyList()

        val scanned = mutableListOf<MessageMember>()
        var last = 0
        for (m in markerRegex.findAll(source)) {
            if (m.range.first > last) {
                scanned += TextMessageMember(source.substring(last, m.range.first))
            }
            memberOf(m)?.let { scanned += it }  // null = 丢弃型标记
            last = m.range.last + 1
        }
        if (last < source.length) {
            scanned += TextMessageMember(source.substring(last))
        }

        // reply 提到最前（保持其余段相对顺序不变），再去空、合并相邻文本
        val replies = scanned.filterIsInstance<ReplyMessageMember>()
        val ordered = if (replies.isEmpty()) {
            scanned
        } else {
            replies + scanned.filterNot { it is ReplyMessageMember }
        }
        return mergeAdjacentText(
            ordered.filterNot { it is TextMessageMember && it.text.isEmpty() }
        )
    }

    private fun memberOf(m: MatchResult): MessageMember? {
        val g = m.groupValues
        return when {
            g[1].isNotEmpty() -> ReplyMessageMember(g[1].toLong(), null)
            m.value == "@全体成员" -> AtMessageMember(0L, null)  // isAtAll：协议层转 qq=all
            g[3].isNotEmpty() -> AtMessageMember(g[3].toLong(), g[2].takeIf { it.isNotEmpty() })
            g[4].isNotEmpty() -> AtMessageMember(g[4].toLong(), null)
            g[5].isNotEmpty() -> FaceMessageMember(g[5].toInt())
            else -> null  // [表情]（无 id）/ [图片]/[表情包]/（#id）：无法重发或不该入正文
        }
    }

    /** 合并相邻纯文本段（丢弃型标记会把文本切碎） */
    private fun mergeAdjacentText(members: List<MessageMember>): List<MessageMember> {
        val out = mutableListOf<MessageMember>()
        for (m in members) {
            val prev = out.lastOrNull()
            if (m is TextMessageMember && prev is TextMessageMember) {
                out[out.lastIndex] = TextMessageMember(prev.text + m.text)
            } else {
                out += m
            }
        }
        return out
    }
}

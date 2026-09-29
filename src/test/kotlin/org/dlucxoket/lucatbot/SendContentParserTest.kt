package org.dlucxoket.lucatbot

import com.mikuac.shiro.enums.MsgTypeEnum
import org.dlucxoket.lucatbot.bot.tool.SendMessageTool
import org.dlucxoket.lucatbot.model.message.SendContentParser
import org.dlucxoket.lucatbot.model.message.member.AtMessageMember
import org.dlucxoket.lucatbot.model.message.member.FaceMessageMember
import org.dlucxoket.lucatbot.model.message.member.ReplyMessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * send content 富消息解析（describe 逆变换）与 OneBot 数组线格式
 *
 * LLM 用与聊天记录相同的标记表达回复/@/表情，这里验证两条链：
 * 标记 → 消息段（[SendContentParser]）→ OneBot 线格式（[SendMessageTool.toWire]）
 */
class SendContentParserTest {

    // ------------------------------------------------------------------
    // 标记 → 消息段
    // ------------------------------------------------------------------

    @Test
    fun `plain text stays single text segment`() {
        val members = SendContentParser.parse("  晚饭吃啥  ")
        assertEquals(1, members.size)
        assertEquals("晚饭吃啥", (members[0] as TextMessageMember).text)
    }

    @Test
    fun `reply marker becomes reply segment and moves to front`() {
        val members = SendContentParser.parse("嗯嗯回复 #581866777：我也是")
        assertTrue(members[0] is ReplyMessageMember, "reply 段提到最前：$members")
        assertEquals(581866777L, (members[0] as ReplyMessageMember).replyToId)
        assertEquals("嗯嗯我也是", members.drop(1).filterIsInstance<TextMessageMember>().joinToString("") { it.text })
    }

    @Test
    fun `reply accepts fullwidth colon and no colon`() {
        assertEquals(
            581866777L,
            (SendContentParser.parse("回复 #581866777：好的")[0] as ReplyMessageMember).replyToId
        )
        assertEquals(
            581866777L,
            (SendContentParser.parse("回复 #581866777 好的")[0] as ReplyMessageMember).replyToId
        )
    }

    @Test
    fun `at with nickname or bare qq becomes at segment`() {
        val named = SendContentParser.parse("@小明(10001) 在吗")
        assertTrue(named[0] is AtMessageMember)
        assertEquals(10001L, (named[0] as AtMessageMember).targetQqNumber)

        val bare = SendContentParser.parse("@2402567904 出来")
        assertEquals(2402567904L, (bare[0] as AtMessageMember).targetQqNumber)

        // 展示名带「（QQ昵称 xx）」注解也能解析（含空格与全角括号）
        val annotated = SendContentParser.parse("@寄（QQ昵称 XENO）(2402567904) 在吗")
        assertTrue(annotated[0] is AtMessageMember, "注解名解析：$annotated")
        assertEquals(2402567904L, (annotated[0] as AtMessageMember).targetQqNumber)
    }

    @Test
    fun `at all members becomes at segment with isAtAll`() {
        val members = SendContentParser.parse("@全体成员 集合")
        assertTrue(members[0] is AtMessageMember)
        assertTrue((members[0] as AtMessageMember).isAtAll(), "@全体成员 → isAtAll")
    }

    @Test
    fun `non at patterns stay text`() {
        // 括号里不是 4+ 位数字 → 不是艾特，原样保留
        val members = SendContentParser.parse("@小明(明天) 我们12点(123456)见")
        assertTrue(members.none { it is AtMessageMember }, "不跨括号误吞：$members")
        val text = members.filterIsInstance<TextMessageMember>().joinToString("") { it.text }
        assertTrue(text.contains("@小明(明天)"), text)
    }

    @Test
    fun `face marker becomes face segment fullwidth colon too`() {
        val half = SendContentParser.parse("哈哈哈[表情:14]")
        assertTrue(half.last() is FaceMessageMember)
        assertEquals(14, (half.last() as FaceMessageMember).faceId)

        val full = SendContentParser.parse("[表情：278]好耶")
        assertTrue(full[0] is FaceMessageMember)
        assertEquals(278, (full[0] as FaceMessageMember).faceId)
    }

    @Test
    fun `unsednable placeholders and line id tags are dropped`() {
        val members = SendContentParser.parse("（#581866777）看这个[图片 file=a.jpg]和[表情包 file=b.meme]还有[表情]")
        assertEquals(1, members.size, "丢弃型标记全部剔除，只剩文本")
        assertEquals("看这个和还有", (members[0] as TextMessageMember).text)
    }

    @Test
    fun `dropped markers do not fragment text`() {
        val members = SendContentParser.parse("前面[图片]后面")
        assertEquals(1, members.size, "丢弃标记后相邻文本应合并")
        assertEquals("前面后面", (members[0] as TextMessageMember).text)
    }

    @Test
    fun `mixed rich content keeps order`() {
        val members = SendContentParser.parse("回复 #111：@小明(10001) 看这个[表情:14] 哈哈")
        assertEquals(5, members.size, "段数量：$members")
        assertTrue(members[0] is ReplyMessageMember, "0=reply")
        assertTrue(members[1] is AtMessageMember, "1=at")
        assertTrue(members[2] is TextMessageMember, "2=text")
        assertTrue(members[3] is FaceMessageMember, "3=face")
        assertTrue(members[4] is TextMessageMember, "4=text")
        assertEquals(111L, (members[0] as ReplyMessageMember).replyToId)
        assertEquals(10001L, (members[1] as AtMessageMember).targetQqNumber)
        assertEquals(14, (members[3] as FaceMessageMember).faceId)
    }

    @Test
    fun `blank or only dropped content parses empty`() {
        assertTrue(SendContentParser.parse("").isEmpty())
        assertTrue(SendContentParser.parse("   ").isEmpty())
        assertTrue(SendContentParser.parse("（#123456）").isEmpty(), "只剩行 id 标记 → 空（发送方按失败返回）")
        assertTrue(SendContentParser.parse("[图片]").isEmpty())
    }

    // ------------------------------------------------------------------
    // 消息段 → OneBot 数组线格式
    // ------------------------------------------------------------------

    @Test
    fun `wire format maps segments to onebot arrays`() {
        val wire = SendMessageTool.toWire(
            SendContentParser.parse("回复 #581866777：@全体成员 冲[表情:14]")
        )
        assertEquals(4, wire.size)

        assertEquals(MsgTypeEnum.reply, wire[0].type)
        assertEquals("581866777", wire[0].getStringData("id"), "reply id 为字符串（OneBot v11）")

        assertEquals(MsgTypeEnum.at, wire[1].type)
        assertEquals("all", wire[1].getStringData("qq"), "@全体成员 → qq=all")

        assertEquals(MsgTypeEnum.text, wire[2].type)
        assertEquals(" 冲", wire[2].getStringData("text"), "标记间文本原样（含分隔空格）")

        assertEquals(MsgTypeEnum.face, wire[3].type)
        assertEquals("14", wire[3].getStringData("id"))
    }

    @Test
    fun `wire at uses qq number not name`() {
        val wire = SendMessageTool.toWire(SendContentParser.parse("@小明(10001) 在吗"))
        assertEquals("10001", wire[0].getStringData("qq"), "线格式只认 QQ 号，名字是 LLM 侧展示")
    }
}

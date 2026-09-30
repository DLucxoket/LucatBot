package org.dlucxoket.lucatbot

import com.mikuac.shiro.enums.MsgTypeEnum
import org.dlucxoket.lucatbot.bot.tool.SendMessageTool
import org.dlucxoket.lucatbot.model.message.OutgoingMember
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * SendMessageTool.buildSegments——结构化消息段 → OneBot 数组消息
 *
 * send 分段发送的 wire 层：条级 reply 提到头部、text 兼容老富标记、
 * at/face 直接构造、未知类型（图片/表情包）丢弃。
 */
class SendMessageToolTest {

    @Test
    fun `reply goes first then text at face in order`() {
        val segs = SendMessageTool.buildSegments(
            581866777L,
            listOf(
                OutgoingMember(type = "text", text = "收到"),
                OutgoingMember(type = "at", qq = "all"),
                OutgoingMember(type = "face", id = "14"),
                OutgoingMember(type = "image", text = "发不了")  // 未知类型丢弃
            )
        )

        assertEquals(4, segs.size, "reply + text + at + face；image 丢弃")
        assertEquals(MsgTypeEnum.reply, segs[0].type, "条级 reply 统一提到头部")
        assertEquals("581866777", segs[0].getStringData("id"))
        assertEquals(MsgTypeEnum.text, segs[1].type)
        assertEquals("收到", segs[1].getStringData("text"))
        assertEquals(MsgTypeEnum.at, segs[2].type)
        assertEquals("all", segs[2].getStringData("qq"), "qq=all → @全体成员")
        assertEquals(MsgTypeEnum.face, segs[3].type)
        assertEquals("14", segs[3].getStringData("id"))
    }

    @Test
    fun `at with numeric qq passes through`() {
        val segs = SendMessageTool.buildSegments(
            null,
            listOf(OutgoingMember(type = "at", qq = "2402567904"))
        )
        assertEquals(1, segs.size)
        assertEquals(MsgTypeEnum.at, segs[0].type)
        assertEquals("2402567904", segs[0].getStringData("qq"))
    }

    @Test
    fun `text still parses legacy rich markers`() {
        // 老标记兼容：text 里的 @ / [表情:n] / 回复 #id：照常转段（QQ 号 ≥4 位才认）
        val segs = SendMessageTool.buildSegments(
            null,
            listOf(OutgoingMember(type = "text", text = "回复 #99：好的 @小明(123456789) [表情:14]"))
        )
        assertTrue(segs.any { it.type == MsgTypeEnum.reply && it.getStringData("id") == "99" },
            "text 内回复标记照常生效：$segs")
        assertTrue(segs.any { it.type == MsgTypeEnum.at && it.getStringData("qq") == "123456789" })
        assertTrue(segs.any { it.type == MsgTypeEnum.face && it.getStringData("id") == "14" })
    }

    @Test
    fun `top level reply suppresses reply parsed from text`() {
        // 一条消息最多一个回复：顶层 reply 与 text 内回复标记并存时取顶层
        val segs = SendMessageTool.buildSegments(
            111L,
            listOf(OutgoingMember(type = "text", text = "回复 #99：好的"))
        )
        val replies = segs.filter { it.type == MsgTypeEnum.reply }
        assertEquals(1, replies.size, "只保留顶层 reply")
        assertEquals("111", replies[0].getStringData("id"))
    }

    @Test
    fun `empty members and blank text yield empty segments`() {
        assertTrue(SendMessageTool.buildSegments(null, emptyList()).isEmpty())
        assertTrue(SendMessageTool.buildSegments(
            null, listOf(OutgoingMember(type = "text", text = ""))
        ).isEmpty(), "空 text 无段（Tool 据此判「消息内容为空」失败）")
        assertTrue(SendMessageTool.buildSegments(
            null, listOf(OutgoingMember(type = "bogus", text = "x"))
        ).isEmpty(), "未知类型全部丢弃 → 空")
    }
}

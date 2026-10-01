package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.tool.FetchUrlRequest
import org.dlucxoket.lucatbot.bot.tool.FetchUrlTool
import org.dlucxoket.lucatbot.bot.tool.SwitchConversationRequest
import org.dlucxoket.lucatbot.bot.tool.SwitchConversationTool
import org.dlucxoket.lucatbot.bot.tool.ToolServices
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.memory.JsonFileMemoryRepository
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 访问链接 / 切换会话 两个新工具：入参校验、错误契约、HTML 正文提取（不碰网络）
 */
class NewToolsTest {

    @Test
    fun `fetch url rejects blank and non-http url with success false`() {
        val blank = FetchUrlTool.processObject(FetchUrlRequest(url = "  "))
        assertFalse(blank.success, "空 url 必须失败")
        assertNotNull(blank.error, "带 error")

        val bad = FetchUrlTool.processObject(FetchUrlRequest(url = "file:///C:/secret.txt"))
        assertFalse(bad.success, "非 http/https 拒绝（防读本地文件）")
        assertNotNull(bad.error)
    }

    @Test
    fun `stripHtml removes tags script and decodes entities`() {
        val html = "<html><head><script>var a=1;</script><style>p{color:red}</style></head>" +
            "<body><h1>标题</h1><p>正文 &amp; 「引号」&nbsp;结尾</p></body></html>"
        val text = FetchUrlTool.stripHtml(html)

        assertFalse(text.contains("<"), "标签全部剥掉：$text")
        assertFalse(text.contains("var a=1") || text.contains("color:red"), "脚本/样式不进正文")
        assertTrue(text.contains("标题") && text.contains("正文"), "正文文字保留：$text")
        assertTrue(text.contains("&"), "实体还原：$text")
        assertFalse(text.contains("&amp;"), "不留实体原样")
    }

    @Test
    fun `switch conversation validates type and target before switching`() {
        val lucatBot = LucatBot()
        SwitchConversationTool.bindServices(
            ToolServices(
                memoryService = MemoryService(
                    JsonFileMemoryRepository(File.createTempFile("mem-sw2", ".json").absolutePath)
                ),
                chatHistoryService = ChatHistoryService(
                    JsonFileChatHistoryRepository(File.createTempFile("hist-sw2", ".json").absolutePath)
                ),
                lucatBot = lucatBot
            )
        )

        val badType = SwitchConversationTool.processObject(SwitchConversationRequest(type = "qqq", target = "123"))
        assertFalse(badType.success, "非法 type 必须失败")
        assertNotNull(badType.error)
        assertNull(lucatBot.pendingConversationSwitch(), "失败不置位")

        val badTarget = SwitchConversationTool.processObject(SwitchConversationRequest(type = "group", target = "abc"))
        assertFalse(badTarget.success, "非数字 target 必须失败")
        assertNull(lucatBot.pendingConversationSwitch())

        val ok = SwitchConversationTool.processObject(
            SwitchConversationRequest(type = "group", target = "811788212", reason = "转去那边")
        )
        assertTrue(ok.success && ok.switched, "合法请求置位成功：$ok")
        val pending = lucatBot.consumeConversationSwitch()
        assertNotNull(pending, "可取走")
        assertEquals("811788212", pending!!.targetId)
        assertNull(lucatBot.pendingConversationSwitch(), "一次性消费")
    }
}

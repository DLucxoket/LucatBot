package org.dlucxoket.lucatbot

import com.mikuac.shiro.core.BotFactory
import org.dlucxoket.lucatbot.adapter.onebot.NapCatOnlineHook
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.tool.SendMessageTool
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.dlucxoket.lucatbot.bot.tool.ToolDependencyBinder
import org.dlucxoket.lucatbot.bot.tool.SendMessageRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.web.socket.WebSocketExtension
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * NapCat **断线清理**守卫
 *
 * 背景（Shiro 2.5.3 字节码实锤）：断线 `afterConnectionClosed` 在默认
 * `wait-bot-connect=0` 下**同步 `attributes.clear()`**（`adapter` 属性被抹掉）
 * 并把 bot 移出容器——还持有该 Bot 的每个 OneBot action 都过
 * `ActionHandler → ConnectionUtils.getAdapter`，必抛
 * `ShiroException: Adapter type wrong`（误导性报错，实为「已断线」）。
 *
 * 故 `offline()` 必须把 `lucatBot.shiroBot` 与 Tool 绑定清成 null：
 * 窗口内动作走 `runTool` 的「Bot 未绑定」干净语义，重连由 `online()` 重绑。
 *
 * 全局单例（LucatBot / Tool.bot / lastBoundBot）在 [cleanup] 复位，
 * 测试造的 Bot 实例绝不留到其它用例。
 */
@SpringBootTest(properties = ["storage.type=json", "mainloop.auto-start=false"])
class NapCatOnlineHookTest {

    @Autowired
    lateinit var hook: NapCatOnlineHook

    @Autowired
    lateinit var lucatBot: LucatBot

    @Autowired
    lateinit var botFactory: BotFactory

    private val selfId = 123456L

    @AfterEach
    fun cleanup() {
        lucatBot.shiroBot = null
        ToolDependencyBinder.unbindBotToAll(selfId)
    }

    @Test
    fun `offline clears stale refs so actions degrade to Bot 未绑定`() {
        val bot = botFactory.createBot(selfId, FakeSession())
        lucatBot.shiroBot = bot
        ToolDependencyBinder.bindBotToAll(bot)
        assertNotNull(toolBot(), "前置：Tool 应已绑定 Bot")

        hook.offline(selfId)

        assertNull(lucatBot.shiroBot, "断线必须清掉 shiroBot（否则撞被清空 adapter 的旧 session → Adapter type wrong）")
        assertNull(toolBot(), "Tool 上绑的旧 Bot 也必须摘掉")
        val r = SendMessageTool.processObject(SendMessageRequest(conversationId = "group:123", content = "hi"))
        assertFalse(r.success, "断线窗口内发送必须失败")
        assertEquals("Bot 未绑定", r.error, "断线窗口内应走干净的「Bot 未绑定」语义，而不是 Adapter type wrong")
    }

    @Test
    fun `offline with other selfId keeps refs`() {
        // 迟到的 offline 回调（或别的账号）不得清掉在用的绑定——
        // 否则刚重连绑好的新 Bot 会被旧回调摘掉
        val bot = botFactory.createBot(selfId, FakeSession())
        lucatBot.shiroBot = bot
        ToolDependencyBinder.bindBotToAll(bot)

        hook.offline(selfId + 1)

        assertSame(bot, lucatBot.shiroBot, "selfId 不符不得清 shiroBot")
        assertNotNull(toolBot(), "selfId 不符不得解绑 Tool")
    }

    /** 反射读 Tool 基类 protected `bot` 字段（不触发真实发送） */
    private fun toolBot(): Any? = Tool::class.java
        .getDeclaredField("bot")
        .apply { isAccessible = true }
        .get(SendMessageTool)

    /** 仅满足 createBot 构造（纯本地对象，无网络） */
    private class FakeSession : WebSocketSession {
        private val attrs: MutableMap<String, Any> = ConcurrentHashMap()
        override fun getId(): String = "fake-session"
        override fun getAttributes(): MutableMap<String, Any> = attrs
        override fun getLocalAddress(): InetSocketAddress? = null
        override fun getRemoteAddress(): InetSocketAddress? = null
        override fun getUri(): java.net.URI? = null
        override fun getPrincipal(): java.security.Principal? = null
        override fun getHandshakeHeaders(): HttpHeaders = HttpHeaders()
        override fun getTextMessageSizeLimit(): Int = 8192
        override fun setTextMessageSizeLimit(messageSizeLimit: Int) = Unit
        override fun getBinaryMessageSizeLimit(): Int = 8192
        override fun setBinaryMessageSizeLimit(messageSizeLimit: Int) = Unit
        override fun getAcceptedProtocol(): String? = null
        override fun getExtensions(): MutableList<WebSocketExtension> = mutableListOf()
        override fun sendMessage(message: WebSocketMessage<*>) = Unit
        override fun isOpen(): Boolean = true
        override fun close() = Unit
        override fun close(status: org.springframework.web.socket.CloseStatus) = Unit
    }
}

package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectRequest
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.chathistory.JsonFileChatHistoryRepository
import org.dlucxoket.lucatbot.bot.chathistory.formatSenderDisplay
import org.dlucxoket.lucatbot.model.event.message.FriendMessageEvent
import org.dlucxoket.lucatbot.model.event.message.GroupMessageEvent
import org.dlucxoket.lucatbot.model.group.Group
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.dlucxoket.lucatbot.model.user.Sex
import org.dlucxoket.lucatbot.model.user.User
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 展示名规则与「群名片 vs QQ昵称」分辨：
 * - 群聊展示名优先群名片、私聊一律 QQ 昵称（入档与 prompt 同一规则）
 * - 同一 QQ 号即同一人；改名不算换人（LLM 约束在两个 prompt 里）
 * - 会话身份行：群聊给群号+群名，私聊给网友 QQ 号+名字
 */
class SenderDisplayNameTest {

    private fun service() = ChatHistoryService(
        JsonFileChatHistoryRepository(File.createTempFile("hist-name", ".json").absolutePath)
    )

    /** 群消息：名片「寄」、QQ 昵称「XENO」的同一个人 */
    private fun groupEvent(card: String?, nickname: String = "XENO", content: String = "在吗") = GroupMessageEvent(
        message = ChatMessage(listOf(TextMessageMember(content))),
        sender = User(2402567904L, nickname, Sex.UNKNOWN, 0, "", card = card),
        group = Group(123L, ""),
        timestamp = 1_000L,
        messageId = 42L
    )

    /** 私聊消息：无名片 */
    private fun privateEvent(nickname: String = "XENO", content: String = "在吗") = FriendMessageEvent(
        message = ChatMessage(listOf(TextMessageMember(content))),
        sender = User(2402567904L, nickname, Sex.UNKNOWN, 0, "", card = null),
        timestamp = 1_000L,
        messageId = 43L
    )

    // ------------------------------------------------------------------
    // formatSenderDisplay：注解规则
    // ------------------------------------------------------------------

    @Test
    fun `display name differs from qq nickname gets annotation`() {
        assertEquals("寄（QQ昵称 XENO）", formatSenderDisplay("寄", "XENO"))
    }

    @Test
    fun `same or missing nickname stays plain`() {
        assertEquals("XENO", formatSenderDisplay("XENO", "XENO"), "私聊/无名片：展示名即 QQ 昵称，不加注解")
        assertEquals("XENO", formatSenderDisplay("XENO", null), "旧数据无昵称字段：原样")
        assertEquals("XENO", formatSenderDisplay("XENO", "  "), "空白昵称不注解")
    }

    // ------------------------------------------------------------------
    // 入档规则：群聊记名片、私聊记 QQ 名
    // ------------------------------------------------------------------

    @Test
    fun `group message records card as display name and keeps qq nickname`() {
        val s = service()
        s.recordMessage(groupEvent(card = "寄"), "在吗")
        val record = s.getHistory("group:123", 10).single()
        assertEquals("寄", record.senderName, "群聊展示名 = 群名片")
        assertEquals("XENO", record.senderNickname, "QQ 昵称另存供注解")
        assertEquals(2402567904L, record.senderId, "身份以 QQ 号为准")
    }

    @Test
    fun `group message without card falls back to qq nickname`() {
        val s = service()
        s.recordMessage(groupEvent(card = null), "在吗")
        val record = s.getHistory("group:123", 10).single()
        assertEquals("XENO", record.senderName, "群名片为空 → QQ 昵称")
        assertEquals("XENO", record.senderNickname)
    }

    @Test
    fun `private message never uses card`() {
        val s = service()
        s.recordMessage(privateEvent(), "在吗")
        val record = s.getHistory("private:2402567904", 10).single()
        assertEquals("XENO", record.senderName, "私聊展示名 = QQ 昵称")
        assertEquals("XENO", record.senderNickname)
    }

    // ------------------------------------------------------------------
    // prompt 摘要：同号不同名时注解成同一人
    // ------------------------------------------------------------------

    @Test
    fun `history summary annotates card with qq nickname`() {
        val s = service()
        s.recordMessage(groupEvent(card = "寄"), "在吗")
        val summary = s.getHistorySummary("group:123", 10)
        assertTrue(summary.contains("寄（QQ昵称 XENO）(2402567904)"), summary)
    }

    @Test
    fun `history summary plain when names match`() {
        val s = service()
        s.recordMessage(privateEvent(), "在吗")
        val summary = s.getHistorySummary("private:2402567904", 10)
        assertTrue(summary.contains("XENO(2402567904)"), summary)
        assertFalse(summary.contains("QQ昵称"), "私聊不加注解：$summary")
    }

    @Test
    fun `peerDisplayName reflects latest name after rename`() {
        val s = service()
        s.recordMessage(groupEvent(card = "寄", content = "第一条"), "第一条")
        assertEquals("寄（QQ昵称 XENO）", s.peerDisplayName("group:123", 2402567904L))

        // 网友改了群名片 → 新消息入档后身份行立刻用新名
        s.recordMessage(groupEvent(card = "新名片", content = "改名了"), "改名了")
        assertEquals("新名片（QQ昵称 XENO）", s.peerDisplayName("group:123", 2402567904L))
    }

    @Test
    fun `peerDisplayName is null for unknown peer`() {
        assertNull(service().peerDisplayName("private:424242", 424242L), "新会话查无对方消息 → null")
    }

    // ------------------------------------------------------------------
    // LLM 约束：同号即同人、改名不是换人
    // ------------------------------------------------------------------

    @Test
    fun `memory agent prompt binds identity to qq number`() {
        val llm = FakeLLM(mutableListOf("""{"memories":[]}"""))
        MemoryAgent(llm).processObject(MemoryReflectRequest(conversationContent = "对话"))
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("同一个 QQ 号就是同一个人"), prompt)
        assertTrue(prompt.contains("改名"), prompt)
        assertFalse(prompt.contains("实为同一人）"), "约束句本身含该短语时排除——只应出现在禁止项语境")
    }

    @Test
    fun `memory agent prompt allows names to be recorded`() {
        // 身份约束只禁「同号当成换了人」类记忆，不能连带把名字挡在记忆外
        val llm = FakeLLM(mutableListOf("""{"memories":[]}"""))
        MemoryAgent(llm).processObject(MemoryReflectRequest(conversationContent = "对话"))
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("名字本身完全可以记"), prompt)
        assertTrue(prompt.contains("群名片叫什么"), prompt)
        assertTrue(prompt.contains("原名 xx"), prompt)
    }

    @Test
    fun `conversation agent prompt explains card vs nickname and rename`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(ConversationRequest())
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("同一个 QQ 号就是同一个人"), prompt)
        assertTrue(prompt.contains("群名片与 QQ 昵称的区分"), prompt)
        assertTrue(prompt.contains("只是改名，不是新的人"), prompt)
    }

    // ------------------------------------------------------------------
    // 会话身份行：群聊给群号+群名，私聊给 QQ 号+名字
    // ------------------------------------------------------------------

    @Test
    fun `conversation prompt shows group identity with group number and name`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(
            ConversationRequest(
                conversationType = "群聊",
                conversationIdentity = "群聊：群号 777，群名「摸鱼基地」",
                conversationId = "group:777"
            )
        )
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("当前会话：群聊：群号 777，群名「摸鱼基地」"), prompt)
    }

    @Test
    fun `conversation prompt shows private identity with qq and name`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(
            ConversationRequest(
                conversationType = "私聊",
                conversationIdentity = "私聊：网友 QQ 2402567904，名字「寄（QQ昵称 XENO）」",
                conversationId = "private:2402567904"
            )
        )
        val prompt = llm.prompts.single()
        assertTrue(prompt.contains("当前会话：私聊：网友 QQ 2402567904"), prompt)
        assertTrue(prompt.contains("名字「寄（QQ昵称 XENO）」"), prompt)
    }

    @Test
    fun `conversation prompt falls back to legacy name line when identity blank`() {
        val llm = FakeLLM(mutableListOf("""{"action":"return"}"""))
        ConversationAgent(llm).processObject(
            ConversationRequest(conversationName = "小明", conversationId = "private:1")
        )
        assertTrue(llm.prompts.single().contains("当前会话：小明(private:1)"))
    }
}

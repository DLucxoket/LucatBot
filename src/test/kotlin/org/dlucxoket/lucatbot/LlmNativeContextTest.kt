package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.agent.data.ConversationRequest
import org.dlucxoket.lucatbot.bot.llm.LlmContextMode
import org.dlucxoket.lucatbot.bot.llm.LlmContextScope
import org.dlucxoket.lucatbot.bot.llm.SharedLlmContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * 上下文模式配置（`llm.context-mode`）与 native 共享上下文守卫
 *
 * 两条底线：
 * 1. **默认/压缩模式行为一个字节不变**——mode 默认 COMPRESSED、
 *    [LlmContextScope.active()] 恒为 null（压缩路径不被拦截）；
 * 2. **native 拆分不丢内容**——Agent 的静态指令段拆出后，
 *    压缩模式下 `buildSystemPrompt` 仍 = 静态段 + "\n\n" + 动态段。
 */
class LlmNativeContextTest {

    @AfterEach
    fun tearDown() {
        LlmContextScope.end()
        LlmContextScope.installMode(LlmContextMode.COMPRESSED)
    }

    @Test
    fun `mode parse is loose and defaults to compressed`() {
        assertEquals(LlmContextMode.COMPRESSED, LlmContextMode.parse(null))
        assertEquals(LlmContextMode.COMPRESSED, LlmContextMode.parse(""))
        assertEquals(LlmContextMode.COMPRESSED, LlmContextMode.parse("garbage"))
        assertEquals(LlmContextMode.NATIVE, LlmContextMode.parse("native"))
        assertEquals(LlmContextMode.COMPRESSED, LlmContextMode.parse(" Compressed "))
    }

    @Test
    fun `compressed mode never exposes a context even after begin`() {
        LlmContextScope.installMode(LlmContextMode.COMPRESSED)
        LlmContextScope.begin("sys", listOf("sys"))
        assertNull(LlmContextScope.active(), "压缩模式下 ask 不得被拦截——active 必须为 null")
        LlmContextScope.noteSystem("没人接得住")
    }

    @Test
    fun `begin end isolates threads and cleans up`() {
        LlmContextScope.installMode(LlmContextMode.NATIVE)
        LlmContextScope.begin("首轮 system", listOf("首轮 system"))
        assertNotNull(LlmContextScope.active(), "本线程能看到上下文")

        // 别的线程看不到（通知跳转/异步记忆整理自动走压缩路径，不串台）
        val other = AtomicReference<SharedLlmContext?>()
        Thread { other.set(LlmContextScope.active()) }.apply { start(); join() }
        assertNull(other.get(), "上下文按线程隔离")

        LlmContextScope.end()
        assertNull(LlmContextScope.active(), "end 后不残留（异常路径也要收口）")
    }

    @Test
    fun `noteSystem appends operation result turns in order`() {
        LlmContextScope.installMode(LlmContextMode.NATIVE)
        LlmContextScope.begin("sys", emptyList())
        val ctx = LlmContextScope.active()!!

        ctx.appendUser("消息列表的用户提示词")
        ctx.appendAssistant("""{"action":"open_conversation"}""")
        LlmContextScope.noteSystem("操作结果：你选择了打开会话")
        ctx.appendUser("聊天界面的用户提示词")
        ctx.appendAssistant("""{"action":"send"}""")
        LlmContextScope.noteSystem("操作结果：消息已发出")

        val roles = ctx.turns.map { it.role.wire }
        assertEquals(listOf("user", "assistant", "system", "user", "assistant", "system"), roles,
            "顺序：user → assistant → 操作结果 system → user → …（用户描述的连贯形态）")
        // 空文本不落笔：上下文里不许出现哑轮
        ctx.appendAssistant("")
        ctx.appendSystem("  ")
        assertEquals(6, ctx.turns.size, "空回复/空结果不入上下文")
    }

    @Test
    fun `stripInstructions removes only the known prefix`() {
        val ctx = SharedLlmContext(
            systemPrompt = "SYS",
            knownInstructions = listOf("PICK指令块", "CONV指令块")
        )
        assertEquals("动态状态\n剧情", ctx.stripInstructions("PICK指令块\n\n动态状态\n剧情"),
            "已随首轮 system 发过的静态指令剥掉")
        assertEquals("别的 Agent 的整段", ctx.stripInstructions("别的 Agent 的整段"),
            "不在已知清单里 → 原样保留（宁可重复不丢状态）")
        assertEquals("", ctx.stripInstructions(null))
        assertEquals("", ctx.stripInstructions("PICK指令块"), "剥完只剩指令也是空串")
    }

    @Test
    fun `compressed prompt stays a single system block after the split`() {
        // 压缩模式（默认路径）：buildSystemPrompt = 静态段 + 动态段，仍是一条 system
        val llm = FakeLLM(mutableListOf("""{"action":"return","reason":"ok"}"""))
        ConversationAgent(llm).processObject(ConversationRequest())
        val recorded = llm.prompts.single()
        val system = recorded.substringBefore("\n=====USER=====\n")

        val agent = ConversationAgent(FakeLLM(mutableListOf()))
        assertTrue(system.startsWith(agent.staticInstructions),
            "拆分后压缩 prompt 仍以静态指令段开头（一个字节不变）")
        assertTrue(system.contains("## 上下文"), "动态状态段照旧拼在后面")
        assertTrue(agent.staticInstructions.contains("## 你的输出格式（严格遵循）"), "静态段就是原指令块")
        assertFalse(agent.staticInstructions.contains("当前时间（北京时间）"),
            "静态段不含任何入参数据（时间/心情/记忆都属于动态段）")
        assertFalse(system.contains("### assistant"), "压缩模式没有 role 标记（不走连贯 messages）")
    }

    @Test
    fun `both loop agents expose non-blank static instructions for the merged system`() {
        val picker = SessionPickerAgent().staticInstructions
        val conv = ConversationAgent(FakeLLM(mutableListOf())).staticInstructions
        assertTrue(picker.isNotBlank() && conv.isNotBlank(), "两个主 Agent 的静态段都要能合并进首轮 system")
        assertTrue(picker.contains("open_conversation"), "选会话指令在静态段里")
        assertTrue(conv.contains("read_more"), "会话内指令在静态段里")
    }
}

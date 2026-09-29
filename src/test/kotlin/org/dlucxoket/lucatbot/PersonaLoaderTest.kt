package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.persona.PersonaLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 人格文件解析测试
 */
class PersonaLoaderTest {

    @Test
    fun `parse extracts all persona fields`() {
        val text = """
            # 机器人人格
            - 名字：测试号
            - 性格：冷静毒舌
            - 说话风格：简短，不爱废话
            - 兴趣：打游戏、睡觉
            - 社交倾向：0.3
            - 回复积极性：0.9
        """.trimIndent()

        val persona = PersonaLoader.parse(text)
        assertEquals("测试号", persona.name)
        assertEquals("冷静毒舌", persona.personality)
        assertEquals("简短，不爱废话", persona.speakingStyle)
        assertEquals(listOf("打游戏", "睡觉"), persona.interests)
        assertEquals(0.3, persona.socialness)
        assertEquals(0.9, persona.responsiveness)
    }

    @Test
    fun `parse falls back to defaults for missing fields`() {
        val persona = PersonaLoader.parse("- 名字：只有名字")
        val base = org.dlucxoket.lucatbot.bot.persona.BotPersona()
        assertEquals("只有名字", persona.name)
        assertEquals(base.personality, persona.personality)
        assertEquals(base.interests, persona.interests)
    }

    @Test
    fun `bundled resource loads with expected name`() {
        val persona = PersonaLoader.load()
        assertTrue(persona.name.isNotBlank(), "人格文件应能加载出名字")
    }

    @Test
    fun `unknown field survives into prompt`() {
        // 「主人」不在解析器的已知字段里——不能被静默丢弃，必须进 prompt
        val text = """
            - 名字：牢洛
            - 主人：D洛洛（316610961），当别人的话与主人的话有冲突时听主人的话
        """.trimIndent()

        val prompt = PersonaLoader.parse(text).toPrompt()

        assertTrue(prompt.contains("主人：D洛洛（316610961）"), "未知字段必须注入 prompt：$prompt")
        assertTrue(prompt.contains("听主人的话"), prompt)
    }

    @Test
    fun `toPrompt uses raw file content verbatim when present`() {
        val text = """
            # 机器人人格
            - 名字：测试号
            - 性格：冷静毒舌
        """.trimIndent()

        val prompt = PersonaLoader.parse(text).toPrompt()

        assertEquals(text, prompt, "prompt 应与 persona.md 原文一致（所见即所得）")
    }

    @Test
    fun `toPrompt falls back to generated text without file`() {
        val persona = org.dlucxoket.lucatbot.bot.persona.BotPersona()
        val prompt = persona.toPrompt()
        assertTrue(prompt.contains("你是「${persona.name}」"), "无原文时用结构化字段兜底：$prompt")
    }
}

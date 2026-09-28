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
}

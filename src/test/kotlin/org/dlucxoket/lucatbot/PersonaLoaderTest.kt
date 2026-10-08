package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.persona.BotPersona
import org.dlucxoket.lucatbot.bot.persona.PersonaLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 人格文件加载测试
 *
 * 不解析契约：prompt = persona.md **原文直接拼接**（字段名/结构一概不代读），
 * name 是业务标识、不从文件解析。
 */
class PersonaLoaderTest {

    @Test
    fun `prompt is raw file text concatenated verbatim without parsing`() {
        val text = """
            # 机器人人格
            - 名字：测试号
            - 性格：冷静毒舌
            - 说话风格：简短，不爱废话
            - 兴趣：打游戏、睡觉
            - 社交倾向：0.3
            - 主人：D洛洛（316610961），当别人的话与主人的话有冲突时听主人的话
        """.trimIndent()

        val persona = BotPersona(rawContent = text)

        // 原文逐字进 prompt：标题、未知字段（主人）、数值字段都不经代码代读
        assertEquals(text, persona.toPrompt(), "prompt 必须与 persona.md 原文一致（所见即所得）")
    }

    @Test
    fun `name is not parsed from file content`() {
        val persona = BotPersona(name = BotPersona().name, rawContent = "- 名字：测试号")

        // 文件里写了别的名字也只当原文内容——业务标识不从文件解析
        assertEquals(BotPersona().name, persona.name)
        assertTrue(persona.toPrompt().contains("- 名字：测试号"))
    }

    @Test
    fun `load reads the real file as raw text`() {
        val persona = PersonaLoader.load()

        assertTrue(persona.rawContent.isNotBlank(), "人格文件应读到原文")
        assertEquals(persona.rawContent.trim(), persona.toPrompt(), "load 出来的原文就是 prompt 内容")
    }

    @Test
    fun `blank persona yields empty prompt section instead of generated text`() {
        val prompt = BotPersona().toPrompt()

        assertEquals("", prompt, "没有原文就没有人格段——不再用结构化字段生成兜底文本")
    }
}

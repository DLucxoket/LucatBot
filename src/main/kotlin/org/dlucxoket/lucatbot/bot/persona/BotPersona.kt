package org.dlucxoket.lucatbot.bot.persona

/**
 * 机器人人格
 *
 * **不解析文件、原文直拼**（persona 内容注入 prompt 的唯一约定）：
 * - [rawContent] = `persona.md` 完整原文，[toPrompt] 原样整段拼进 prompt——
 *   文件里写了什么，模型就看到什么，代码不代读任何字段
 * - 结构化字段（性格/兴趣/社交倾向/回复积极性…）与「兜底渲染」**已删除**：
 *   解析了没有消费方，还会造成「文件改了 prompt 没变」的排查错觉
 * - [name] 是**业务标识**（聊天记录里自己消息的署名、操作日志），
 *   **不从文件解析**——要改名改这里的默认值
 */
data class BotPersona(
    /** 名字（业务标识：聊天记录中自己消息的署名）——不从 persona.md 解析 */
    val name: String = "牢洛",

    /** persona.md 的完整原文（[toPrompt] 直接整段拼进 prompt；文件缺失 = 空串） */
    val rawContent: String = ""
) {
    /**
     * 注入 LLM 的人格段 = **原文直接拼接**（只去首尾空白，不解析、不改写、不代读字段）
     */
    fun toPrompt(): String = rawContent.trim()
}

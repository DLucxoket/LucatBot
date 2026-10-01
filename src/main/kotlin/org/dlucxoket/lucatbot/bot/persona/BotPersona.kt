package org.dlucxoket.lucatbot.bot.persona

/**
 * 机器人人格
 *
 * **单一真相源定案**：
 * - **persona.md 原文**（[rawContent]）——文件存在时 [toPrompt] 整段注入，
 *   任何字段（如「主人」）都不会丢；文件里写了什么，prompt 就是什么
 * - **[name] 例外**——业务标识（聊天记录里的「我」、操作日志），单独解析
 * - **其余结构化字段**——仅作「persona.md 缺失」时兜底 prompt 的代码默认值，
 *   不从文件解析（解析了没有消费方）；KDoc 不再声称它们影响调度
 */
data class BotPersona(
    /** 名字（业务标识：聊天记录中的「我」、会话内称呼） */
    val name: String = "牢洛",

    /** 性格关键词——仅兜底 prompt 默认值 */
    val personality: String = "活泼开朗，偶尔犯懒，说话带点俏皮",

    /** 兴趣爱好——仅兜底 prompt 默认值 */
    val interests: List<String> = listOf("聊天", "摸鱼"),

    /** 社交倾向 0.0=社恐, 1.0=社牛——仅兜底 prompt 默认值 */
    val socialness: Double = 0.6,

    /** 回复积极性 0.0=很懒, 1.0=秒回——仅兜底 prompt 默认值 */
    val responsiveness: Double = 0.7,

    /** 说话风格——仅兜底 prompt 默认值 */
    val speakingStyle: String = "口语化，简洁，像真人打字，可以用语气词",

    /**
     * persona.md 的完整原文（非空时 [toPrompt] 直接整段注入）
     *
     * 目的：prompt 以文件为准——文件里写了什么就注入什么，
     * 解析器不认识的字段（如「主人：xxx」）不会被静默丢弃。
     */
    val rawContent: String = ""
) {
    /**
     * 生成注入 LLM 的人格描述
     *
     * - 有原文（persona.md 加载成功）→ **整段原文直接注入**，所见即所得
     * - 无原文（文件缺失）→ 用结构化字段拼出兜底描述
     */
    fun toPrompt(): String {
        rawContent.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return """
            你是「$name」。
            性格：$personality
            兴趣：${interests.joinToString("、")}
            说话风格：$speakingStyle
            社交倾向：${if (socialness > 0.7) "很喜欢和人聊天" else if (socialness > 0.4) "正常社交" else "有点社恐"}
            回复积极性：${if (responsiveness > 0.7) "很积极回复" else if (responsiveness > 0.4) "正常回复" else "有时候懒得回"}
        """.trimIndent()
    }
}

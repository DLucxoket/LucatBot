package org.dlucxoket.lucatbot.bot.persona

/**
 * 机器人人格
 *
 * **模拟真人的性格特征**，影响：
 * - 选哪个会话进入（社牛爱聊私聊，社恐优先回复@自己的）
 * - 说话风格（活泼/高冷/温柔/毒舌）
 * - 回复频率（爱聊的人回得多，忙的人回得少）
 *
 * 双来源：
 * - **persona.md 原文**（[rawContent]，正常路径）——[toPrompt] 整段注入，
 *   任何字段（如「主人」）都不会被解析器丢弃；
 * - **代码内置默认值**（文件缺失时）——结构化字段拼出兜底 prompt。
 *
 * 结构化字段（name 等）仍会被解析：`name` 用于聊天记录里的「我」等业务标识。
 */
data class BotPersona(
    /** 名字 */
    val name: String = "牢洛",

    /** 性格关键词（影响说话风格） */
    val personality: String = "活泼开朗，偶尔犯懒，说话带点俏皮",

    /** 兴趣爱好（影响对什么话题感兴趣） */
    val interests: List<String> = listOf("聊天", "摸鱼", "吐槽"),

    /** 社交倾向 0.0=社恐, 1.0=社牛（影响选会话偏好） */
    val socialness: Double = 0.6,

    /** 回复积极性 0.0=很懒, 1.0=秒回（影响回复概率和速度） */
    val responsiveness: Double = 0.7,

    /** 说话风格描述（直接注入 LLM prompt） */
    val speakingStyle: String = "口语化，简洁，像真人打字，可以用语气词",

    /**
     * persona.md 的完整原文（非空时 [toPrompt] 直接整段注入）
     *
     * 目的：prompt 以文件为准——文件里写了什么就注入什么，
     * 解析器不认识的字段（如「主人：xxx」）不再被静默丢弃。
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

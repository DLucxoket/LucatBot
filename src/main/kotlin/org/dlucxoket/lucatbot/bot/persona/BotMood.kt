package org.dlucxoket.lucatbot.bot.persona

import java.time.LocalTime

/**
 * 机器人心情
 *
 * **模拟真人的情绪波动**，影响：
 * - 回复意愿（开心多聊，疲惫不想理）
 * - 说话语气（开心加emoji，不开心变冷淡）
 * - 选会话偏好（开心爱私聊，累了只回@自己的）
 *
 * 心情会随时间自然变化。
 */
data class BotMood(
    /** 心情类型 */
    val type: MoodType = MoodType.NORMAL,

    /** 心情强度 0.0-1.0 */
    val intensity: Double = 0.5,

    /** 心情持续到的时间戳 */
    val expiresAt: Long = 0L
) {
    /** 心情类型 */
    enum class MoodType {
        HAPPY,      // 开心
        NORMAL,     // 普通
        BORED,      // 无聊
        TIRED,      // 疲惫
        ANNOYED,    // 烦躁
        SLEEPY      // 困了
    }

    /**
     * 生成注入 LLM 的心情描述：文字 + 强度数值（保留两位小数）
     *
     * 只用文字不用 emoji——prompt 里带 😊 会被 LLM 模仿、句末跟着输出笑脸。
     * 强度以数值形式给智能体（如「困了（强度 0.90）」），
     * 去留、回不回等行为判断交给 LLM，不在代码里写死阈值。
     */
    fun toPrompt(): String {
        val text = when (type) {
            MoodType.HAPPY -> "现在心情不错"
            MoodType.NORMAL -> "心情一般"
            MoodType.BORED -> "有点无聊"
            MoodType.TIRED -> "有点累了"
            MoodType.ANNOYED -> "心情不太好"
            MoodType.SLEEPY -> "困了"
        }
        return "$text（强度 ${String.format(java.util.Locale.ROOT, "%.2f", intensity)}）"
    }

    /**
     * 心情对回复意愿的影响（0.0-1.0）
     */
    fun replyWillingness(): Double {
        val base = when (type) {
            MoodType.HAPPY -> 1.0
            MoodType.NORMAL -> 0.8
            MoodType.BORED -> 0.6
            MoodType.TIRED -> 0.4
            MoodType.ANNOYED -> 0.3
            MoodType.SLEEPY -> 0.2
        }
        return (base * (0.5 + intensity * 0.5)).coerceIn(0.0, 1.0)
    }

    companion object {
        /**
         * 根据时间自动推断初始心情
         */
        fun guessByTime(): BotMood {
            val hour = LocalTime.now().hour
            return when {
                hour in 6..9 -> BotMood(MoodType.NORMAL, 0.6)       // 早上
                hour in 10..12 -> BotMood(MoodType.HAPPY, 0.5)      // 上午
                hour in 13..14 -> BotMood(MoodType.SLEEPY, 0.4)     // 午后
                hour in 15..18 -> BotMood(MoodType.NORMAL, 0.5)     // 下午
                hour in 19..22 -> BotMood(MoodType.HAPPY, 0.6)      // 晚上
                hour in 23..24 || hour in 0..2 -> BotMood(MoodType.SLEEPY, 0.7) // 深夜
                else -> BotMood(MoodType.SLEEPY, 0.8)               // 凌晨
            }
        }
    }

    /**
     * 被聊天——心情变好
     *
     * 模拟真人和朋友聊天后心情提升。
     */
    fun boost(): BotMood {
        val newIntensity = (intensity + 0.1).coerceAtMost(1.0)
        val newType = when {
            type == MoodType.TIRED || type == MoodType.SLEEPY -> type  // 累了聊天也不会变精神
            type == MoodType.ANNOYED -> MoodType.NORMAL               // 烦躁被安抚
            newIntensity > 0.7 -> MoodType.HAPPY
            else -> type
        }
        return copy(type = newType, intensity = newIntensity)
    }

    /**
     * 被冷落——心情变差
     *
     * 模拟真人被已读不回/被忽视后心情下降。
     */
    fun drop(): BotMood {
        val newIntensity = (intensity - 0.15).coerceAtLeast(0.1)
        val newType = when {
            type == MoodType.HAPPY && newIntensity < 0.5 -> MoodType.NORMAL
            type == MoodType.NORMAL && newIntensity < 0.3 -> MoodType.BORED
            type == MoodType.BORED && newIntensity < 0.2 -> MoodType.ANNOYED
            else -> type
        }
        return copy(type = newType, intensity = newIntensity)
    }

    /**
     * 自然衰减——心情随时间回归平常
     */
    fun decay(): BotMood {
        return copy(intensity = (intensity * 0.95).coerceAtLeast(0.3))
    }
}

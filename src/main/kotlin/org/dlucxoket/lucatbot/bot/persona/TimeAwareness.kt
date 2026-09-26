package org.dlucxoket.lucatbot.bot.persona

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 时间感知
 *
 * **模拟真人的时间感**：
 * - 深夜不回消息 / 回得很慢
 * - 工作时间可能不回 / 回得简短
 * - 周末更爱聊天
 * - 不会秒回（有打字延迟）
 */
object TimeAwareness {

    /** 当前是否是深夜（23:00-07:00） */
    fun isLateNight(): Boolean {
        val hour = LocalTime.now().hour
        return hour >= 23 || hour < 7
    }

    /** 当前是否是工作时间（周一到周五 9:00-18:00） */
    fun isWorkTime(): Boolean {
        val now = LocalDateTime.now()
        val dayOfWeek = now.dayOfWeek.value // 1=Monday, 7=Sunday
        val hour = now.hour
        return dayOfWeek in 1..5 && hour in 9..18
    }

    /** 当前是否是周末 */
    fun isWeekend(): Boolean {
        val dayOfWeek = LocalDateTime.now().dayOfWeek.value
        return dayOfWeek in 6..7
    }

    /**
     * 计算打字延迟（毫秒）
     *
     * 模拟真人打字速度：不会秒回，打字需要时间。
     *
     * @param replyLength 回复内容长度
     * @return 建议延迟毫秒数
     */
    fun typingDelay(replyLength: Int): Long {
        // 每个字约 100-300ms 打字时间
        val baseDelay = replyLength * 150L
        // 加上思考时间 1-5 秒
        val thinkingTime = (1000..5000).random()
        // 深夜更慢
        val nightPenalty = if (isLateNight()) 2.0 else 1.0
        return ((baseDelay + thinkingTime) * nightPenalty).toLong().coerceIn(1000, 15000)
    }

    /**
     * 是否应该延迟回复（模拟真人不秒回）
     */
    fun shouldDelay(): Boolean {
        // 30% 概率故意延迟
        return Math.random() < 0.3
    }
}

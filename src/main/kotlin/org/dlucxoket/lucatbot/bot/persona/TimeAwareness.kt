package org.dlucxoket.lucatbot.bot.persona

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 时间感知
 *
 * **模拟真人的时间感**：深夜不主动查消息（[ProactiveScheduler] 深夜跳过）。
 * 决策 prompt 的当前时间由 [timeHint] 提供。
 *
 * 曾有打字延迟/工作时间等规划方法，长期零调用已删（YAGNI，git 可找回）；
 * 真需要时再按需添加，不预留死分支。
 */
object TimeAwareness {

    /**
     * 当前是否是深夜（23:00~07:00）——深夜不主动检查未读，模拟真人睡觉
     *
     * 当前为测试期间，永为false
     */
    fun isLateNight(): Boolean {
        //测试期间永远返回false
//        val hour = LocalTime.now().hour
//        return hour >= 23 || hour < 7
        return false
    }

    /** 决策 prompt 用时间提示（北京时间 yyyyMMdd-HHmmss） */
    fun timeHint(): String {
        return LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    }
}

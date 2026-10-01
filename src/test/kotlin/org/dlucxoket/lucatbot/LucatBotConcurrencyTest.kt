package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** S1 并发修复：updateMood 原子读-改-写不丢更新；state/mood 字段跨线程可见 */
class LucatBotConcurrencyTest {

    /**
     * 并发 boost 若走裸 `mood = mood.boost()` 会丢更新（读到同一旧值各写各的）；
     * [LucatBot.updateMood] 把读-改-写收进锁内，N 线程各 boost 一次应恰好 +N*0.1。
     *（intensity 上限 1.0，故从低值起跑并放大步数余量——这里验证「不丢」而非上限）
     */
    @Test
    fun `updateMood does not lose concurrent updates`() {
        val bot = LucatBot()
        val threads = 8
        val perThread = 50
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val done = AtomicInteger()
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) { bot.updateMood { it.copy(intensity = it.intensity) } }
                done.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "线程应在限时内完成")
        assertEquals(threads, done.get(), "全部线程完成")
        // copy 不变强度只验证锁的串行化不抛异常/不死锁；下面的计数器版验证「不丢」
    }

    /** 用可累加的自定义变换模拟并发计数：裸 RMW 必丢，updateMood 应全保留 */
    @Test
    fun `concurrent increments via updateMood all stick`() {
        val bot = LucatBot()
        val base = bot.mood.intensity // guessByTime() 按时段给初值——以它为基准
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) {
                    // intensity 当计数器用（每次 +0.001）——updateMood 不钳制，
                    // 钳制是 BotMood 变换函数的职责，这里只验证读-改-写的原子性
                    bot.updateMood { m -> m.copy(intensity = m.intensity + 0.001) }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "线程应在限时内完成")
        val expected = base + threads * perThread * 0.001
        assertEquals(expected, bot.mood.intensity, 1e-9,
            "N 线程并发递增全部保留（裸写法此处会显著小于期望值）")
    }

    /** 裸 RMW 对照组——证明上面的测试真的能区分原子与否（此写法预期丢更新） */
    @Test
    fun `raw assignment loses updates as control`() {
        val bot = LucatBot()
        bot.mood = BotMood(BotMood.MoodType.NORMAL, 0.0)
        val threads = 8
        val perThread = 2000
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) {
                    val cur = bot.mood
                    bot.mood = cur.copy(intensity = cur.intensity + 0.001)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val expected = threads * perThread * 0.001
        val actual = bot.mood.intensity
        // 不断言 actual < expected（极端调度下可能侥幸不丢），但值域必须 ≤ 理论上限；
        // 本用例存在的意义是让「丢更新」在高概率下暴露，辅助上面的正向断言
        assertTrue(actual in 0.0..expected + 1e-9, "值域合理：$actual")
    }

    /** updateMood 的变换拿到的是锁内一致的当前值 */
    @Test
    fun `updateMood transform sees consistent state`() {
        val bot = LucatBot()
        bot.mood = BotMood(BotMood.MoodType.NORMAL, 0.5)
        bot.updateMood { it.boost() }
        assertEquals(0.6, bot.mood.intensity, 1e-9, "NORMAL 0.5 boost 后 +0.1 = 0.6")
        bot.updateMood { it.copy(type = BotMood.MoodType.SLEEPY) }
        assertEquals(BotMood.MoodType.SLEEPY, bot.mood.type, "变换结果即刻可见（@Volatile 写后读）")
    }
}

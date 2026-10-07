package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.adapter.web.TokenUsageController
import org.dlucxoket.lucatbot.bot.llm.OpenAIAPI
import org.dlucxoket.lucatbot.bot.llm.TokenUsageAggregator
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(properties = ["storage.type=json", "mainloop.auto-start=false"])
/** Spring 容器启动冒烟测试（固定 json 后端，测试不依赖 MySQL；关闭主循环就绪自启，
 *  避免测试上下文真起循环打 LLM——MySQL 路径见 MySqlStorageSmokeTest） */
class LucatBotApplicationTests {

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun contextLoads() {
    }

    /**
     * token 账本的**装配守卫**（DIP 回归测试）
     *
     * `OpenAIAPI` 有一个无参次构造（非 Spring 兜底）——若 Spring 挑了它而不是标了
     * `@Autowired` 的主构造，账本会静默写进**另一个内存实例**：日志照常打用量、
     * 面板 `/api/token/summary` 却恒为 0，且不会有任何报错。这里断言三个消费者
     * 拿到的是**容器里同一个 bean**。
     */
    @Test
    fun `token usage aggregator is wired into every consumer`() {
        val bean = context.getBean(TokenUsageAggregator::class.java)
        assertSame(bean, injected(context.getBean(OpenAIAPI::class.java), "tokenUsage"),
            "OpenAIAPI 必须用容器里的聚合器（而不是无参次构造的内存账本）")
        assertSame(bean, injected(context.getBean(TokenUsageController::class.java), "tokenUsage"),
            "面板 Controller 与 OpenAIAPI 必须是同一份统计，否则面板恒为 0")
        assertSame(bean, injected(context.getBean(org.dlucxoket.lucatbot.bot.loop.ProgramShutdown::class.java), "tokenUsage"),
            "停机终值汇总也要打同一份账")
    }

    /** 取构造注入进来的私有字段值 */
    private fun injected(target: Any, field: String): Any {
        val f = target::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.get(target)
    }
}

package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.adapter.web.TokenUsageController
import org.dlucxoket.lucatbot.bot.llm.TokenUsageAggregator
import org.dlucxoket.lucatbot.bot.loop.ConversationController
import org.dlucxoket.lucatbot.bot.loop.MessageListController
import org.dlucxoket.lucatbot.bot.loop.NotificationDispatcher
import org.dlucxoket.lucatbot.bot.loop.ProgramShutdown
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
     * 五个 Agent 各自带默认账本（不落盘的内存实现）——LoopConfig 必须把容器里
     * 同一个 bean 注进去，否则每个 Agent 各记各的：日志看着有数、面板却只显示
     * 其中一两份，且**不会有任何报错**。这里断言所有消费者拿到的是同一个实例。
     */
    @Test
    fun `token usage aggregator is wired into every consumer`() {
        val bean = context.getBean(TokenUsageAggregator::class.java)

        val conversation = context.getBean(ConversationController::class.java)
        assertSame(bean, agentLedger(conversation, "conversationAgent"), "会话决策智能体")
        assertSame(bean, agentLedger(conversation, "memoryAgent"), "退出整理智能体")
        assertSame(bean, agentLedger(conversation, "memoryKeywordAgent"), "进场拆词智能体")
        assertSame(
            bean,
            agentLedger(context.getBean(MessageListController::class.java), "sessionPickerAgent"),
            "选会话智能体"
        )
        assertSame(
            bean,
            agentLedger(context.getBean(NotificationDispatcher::class.java), "notificationAgent"),
            "通知智能体"
        )

        assertSame(bean, injected(context.getBean(ProgramShutdown::class.java), "tokenUsage"), "停机终值汇总")
        assertSame(bean, injected(context.getBean(TokenUsageController::class.java), "tokenUsage"), "面板 Controller")
    }

    /** 取控制器里的某个 agent，再取它（可能在基类 [org.dlucxoket.lucatbot.bot.agent.Agent]）的账本字段 */
    private fun agentLedger(controller: Any, agentField: String): Any =
        injected(injected(controller, agentField), "tokenUsage")

    /** 沿继承链取私有字段值（`tokenUsage` 声明在 Agent 基类） */
    private fun injected(target: Any, field: String): Any {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val f = type.declaredFields.firstOrNull { it.name == field }
            if (f != null) {
                f.isAccessible = true
                return f.get(target)
            }
            type = type.superclass
        }
        throw AssertionError("字段 $field 在 ${target.javaClass.name} 及其父类里都找不到")
    }
}

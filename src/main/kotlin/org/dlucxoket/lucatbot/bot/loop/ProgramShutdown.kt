package org.dlucxoket.lucatbot.bot.loop

import jakarta.annotation.PreDestroy
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 停机收尾——程序停止时保证「没记录的记忆先记录」
 *
 * Spring 容器关闭时（Ctrl+C / SIGTERM / gradle 停止）按序执行：
 *
 * 1. **停主循环**：不再进入新决策，收尾期间不会产生新的会话/写入
 * 2. **排干异步整理**：退出会话时提交的异步记忆整理（[MemoryReflector.reflectAsync]）
 *    可能还在排队/执行——先等它落地（超时兜底继续），否则记忆会随 JVM 退出丢失
 * 3. **补记忆整理**：若程序停在某个会话里，退出时的 [org.dlucxoket.lucatbot.bot.agent.MemoryAgent]
 *    整理还没跑过——这是唯一真正「还没记录」的记忆，停机前补一次（幂等，
 *    正常退出已整理过则跳过）
 * 4. **立即落盘**：记忆 / 聊天记录 / 待办三个服务全部 flush，
 *    不等 5s 防抖窗口（仓库 bean 自身的 destroyMethod=shutdown 是第二重兜底，
 *    本类因依赖关系更晚创建、更早销毁，顺序在它之前）
 *
 * 每步独立 try/catch：任何一步失败都不阻断后面的落盘。
 */
@Component
class ProgramShutdown(
    private val mainLoopController: MainLoopController,
    private val conversationController: ConversationController,
    private val memoryService: MemoryService,
    private val chatHistoryService: ChatHistoryService,
    private val todoService: TodoService
) {
    private val log = LoggerFactory.getLogger(ProgramShutdown::class.java)

    @PreDestroy
    fun onStop() {
        log.info("程序停止：开始收尾（停主循环 → 排干异步整理 → 补记忆整理 → 落盘）")

        step("停主循环") { mainLoopController.stop() }
        // 退出路径提交的异步记忆整理：先排队落地（LLM 调用给足 30s，超时不阻断后续）
        step("排干异步记忆整理") {
            val drained = conversationController.awaitPendingReflects(30_000L)
            if (!drained) log.warn("异步记忆整理等待超时，继续停机（可能丢失未完成的整理）")
        }
        // 停在会话中时，这里补做退出记忆整理（内部走 LLM，失败兜底为空结果）
        step("补记忆整理") { conversationController.reflectForShutdown() }
        step("落盘·记忆") { memoryService.flush() }
        step("落盘·聊天记录") { chatHistoryService.flush() }
        step("落盘·待办") { todoService.flush() }

        log.info("程序停止：记忆/聊天记录/待办已全部落盘")
    }

    private fun step(name: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log.warn("停机收尾 [{}] 失败（继续执行后续步骤）: {}", name, e.message)
        }
    }
}

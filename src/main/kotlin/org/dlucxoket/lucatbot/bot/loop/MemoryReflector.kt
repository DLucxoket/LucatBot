package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectRequest
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.slf4j.LoggerFactory

/**
 * 退出会话时的记忆整理（MemoryAgent 编排）——从 ConversationController 抽出的单一职责组件
 *
 * 职责：
 * - 把会话内容交给 [MemoryAgent] 判断值得长期记住的信息，按 kind 写入 [MemoryService]
 * - **幂等**：同一会话只整理一次（正常退出与停机收尾可能并发触发，
 *   [reflectLock] + 会话标识去重保证不重复写入）
 * - **失败不影响退出**：LLM 异常/解析失败一律兜底为空结果，整体再套
 *   try/catch，只 log.warn——绝不阻塞「返回消息列表」
 *
 * 被 [ConversationController] 组合使用（会话进入时 [beginConversation] 重置幂等，
 * 正常退出 [reflect]，停机收尾也走 [reflect]）。
 */
class MemoryReflector(
    private val memoryAgent: MemoryAgent,
    private val memoryService: MemoryService,
    private val chatHistoryService: ChatHistoryService,
    private val actionLog: ActionLog,
    /** 退出时记忆整理读取的聊天记录条数 */
    private val reflectHistoryCount: Int = 30
) {
    private val log = LoggerFactory.getLogger(MemoryReflector::class.java)

    /** 幂等保护锁：正常退出与停机收尾对同一会话只整理一次 */
    private val reflectLock = Any()

    @Volatile
    private var lastReflected: String? = null

    /** 进入新会话时调用：重置幂等保护（同一会话再次进入时允许重新整理） */
    fun beginConversation() {
        lastReflected = null
    }

    /**
     * 整理一次（幂等入口）：同会话已整理过则直接跳过
     */
    fun reflect(conversationId: String) {
        synchronized(reflectLock) {
            if (lastReflected == conversationId) {
                log.debug("会话 {} 的记忆已整理过，跳过重复整理", conversationId)
                return
            }
            doReflect(conversationId)
            lastReflected = conversationId
        }
    }

    /** 记忆整理的实际执行（仅在 [reflectLock] 内调用） */
    private fun doReflect(conversationId: String) {
        try {
            val subject = MemorySubject.fromConversation(conversationId)
            val result = memoryAgent.processObject(
                MemoryReflectRequest(
                    conversationContent = chatHistoryService.getHistorySummary(
                        conversationId, reflectHistoryCount
                    ),
                    // 判重视野调大（默认 5/3/5/3 太小）：LLM 看不见的已有记忆
                    // 会被当成新的再记一遍——反复记录的来源之一
                    existingMemory = memoryService.getMemorySummary(
                        subject,
                        factLimit = 50, opinionLimit = 30, eventLimit = 50, relationLimit = 20
                    ),
                    timeHint = TimeAwareness.timeHint()
                )
            )
            if (result.memories.isEmpty()) {
                log.debug("退出时记忆整理：无值得记录的内容")
                return
            }
            var written = 0
            for (item in result.memories) {
                when (item.kind.lowercase()) {
                    "fact" -> {
                        memoryService.rememberFact(subject, item.content, item.tags)
                        written++
                    }
                    "opinion" -> {
                        memoryService.rememberOpinion(subject, item.content)
                        written++
                    }
                    "event" -> {
                        memoryService.rememberEvent(subject, item.content)
                        written++
                    }
                    else -> log.debug("退出时记忆整理：跳过未知 kind={}（content={}）", item.kind, item.content)
                }
            }
            log.info("退出时记忆整理：写入 {} 条", written)
            if (written > 0) {
                actionLog.append("整理记忆 $written 条")
                // 窗口行打标「已记忆」：本轮已整理过这段对话，下次退出整理
                // 看到标记不再改写复读（写入判重是精确匹配，改写式重复拦不住）
                chatHistoryService.markWindowMemorized(conversationId, reflectHistoryCount)
            }
        } catch (e: Exception) {
            log.warn("退出时记忆整理失败（忽略，不影响返回消息列表）: {}", e.message)
        }
    }
}

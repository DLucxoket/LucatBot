package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.agent.MemoryAgent
import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectItem
import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectRequest
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.persona.TimeAwareness
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 退出会话时的记忆整理（MemoryAgent 编排）——从 ConversationController 抽出的单一职责组件
 *
 * 职责：
 * - **异步不阻塞**：正常退出经 [reflectAsync] 把整理任务丢进单线程 daemon 执行器
 *   立即返回——「返回消息列表 / 主循环下一步」不等 LLM；停机收尾仍走同步 [reflect]
 *   （`awaitPending` 排干排队任务后再补做，保证不丢记忆）
 * - **两阶段写入**：阶段1 复盘产出候选（含检索关键词）→ 用关键词查库命中已有
 *   记忆 → 阶段2 把命中回喂 [MemoryAgent] 复核，过滤改写式重复后再写入
 *   （写入时 [MemoryService] 的精确判重仍是最后一道防线）
 * - **幂等**：同一会话只整理一次（正常退出与停机收尾可能并发触发，
 *   [reflectLock] + 会话标识去重保证不重复写入）
 * - **失败不影响退出**：LLM 异常/解析失败一律兜底，整体再套 try/catch，
 *   只 log.warn——绝不阻塞「返回消息列表」；复核阶段失败**保守放行**候选，
 *   绝不因复核故障把记忆整批吞掉
 *
 * 被 [ConversationController] 组合使用（会话进入时 [beginConversation] 重置幂等，
 * 正常退出 [reflectAsync]，停机收尾走 [reflect] + [awaitPending]）。
 */
class MemoryReflector(
    private val memoryAgent: MemoryAgent,
    private val memoryService: MemoryService,
    private val chatHistoryService: ChatHistoryService,
    private val actionLog: ActionLog,
    /** 退出时记忆整理读取的聊天记录条数 */
    private val reflectHistoryCount: Int = 30,
    /**
     * 关键词复核的命中回喂上限（最新优先）——关键词扩容后实盘命中曾达 77 条，
     * 全量回喂会把复核 prompt 撑爆；判重只需最近的相似记忆
     */
    private val maxReviewHits: Int = 30,
    /**
     * 整理任务执行器——默认单线程 daemon（[asyncExecutor]，串行 + 不阻塞主循环）；
     * 测试注入内联执行器（`Executor { it.run() }`）即变同步，断言无竞态
     */
    private val executor: Executor = asyncExecutor()
) {
    private val log = LoggerFactory.getLogger(MemoryReflector::class.java)

    /** 幂等保护锁：正常退出与停机收尾对同一会话只整理一次 */
    private val reflectLock = Any()

    @Volatile
    private var lastReflected: String? = null

    /** 异步任务计数（排队 + 执行中）——[awaitPending] 用 */
    private val pendingLock = Object()
    private var pendingTasks = 0

    /** 进入新会话时调用：重置幂等保护（同一会话再次进入时允许重新整理） */
    fun beginConversation() {
        lastReflected = null
    }

    /**
     * 异步整理（正常退出路径）：提交后立即返回，**不阻塞主循环下一步**
     *
     * 同会话幂等保护与同步版共用（任务体即 [reflect]）；执行器拒绝
     * （已停机）只 log，不抛——停机路径另有同步补做兜底。
     */
    fun reflectAsync(conversationId: String) {
        synchronized(pendingLock) { pendingTasks++ }
        try {
            executor.execute {
                try {
                    reflect(conversationId)
                } finally {
                    synchronized(pendingLock) {
                        pendingTasks--
                        pendingLock.notifyAll()
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            synchronized(pendingLock) {
                pendingTasks--
                pendingLock.notifyAll()
            }
            log.warn("记忆整理任务提交失败（执行器已关闭？）: {}", e.message)
        }
    }

    /**
     * 等待已提交的异步整理落地（停机收尾在 [reflectForShutdown] 之前调用）
     *
     * @param timeoutMs 最长等待毫秒
     * @return true = 全部落地；false = 超时仍有任务在跑
     */
    fun awaitPending(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(pendingLock) {
            while (pendingTasks > 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                pendingLock.wait(remaining)
            }
        }
        return true
    }

    /**
     * 同步整理（幂等入口）：同会话已整理过则直接跳过
     *
     * 停机收尾路径专用（必须等它写完才能落盘退出）；正常退出用 [reflectAsync]。
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

            // 阶段1：复盘产出候选（每条带检索关键词）
            val candidates = memoryAgent.processObject(
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
            ).memories
            if (candidates.isEmpty()) {
                log.debug("退出时记忆整理：无值得记录的内容")
                return
            }

            // 阶段2：写入前关键词查库复核——拦改写式重复（精确判重拦不住的）
            val toWrite = reviewWithKeywordSearch(candidates, subject)
            if (toWrite.isEmpty()) {
                log.debug("退出时记忆整理：{} 条候选经关键词复核后无新增", candidates.size)
                return
            }

            var written = 0
            for (item in toWrite) {
                // keywords 转正落库：阶段1的检索关键词（5~10 个）与 tags 合并写入——
                // 落库的关键词（tags/memory_tags）才是 search 命中的另一半，
                // 只用来查库不入库等于让后续每条记忆的关键词都缺一半（tags 还常被 LLM 省略）
                val effectiveTags = (item.tags + item.keywords).distinct()
                when (item.kind.lowercase()) {
                    "fact" -> {
                        memoryService.rememberFact(subject, item.content, effectiveTags)
                        written++
                    }
                    "opinion" -> {
                        memoryService.rememberOpinion(subject, item.content, effectiveTags)
                        written++
                    }
                    "event" -> {
                        memoryService.rememberEvent(subject, item.content, tags = effectiveTags)
                        written++
                    }
                    else -> log.debug("退出时记忆整理：跳过未知 kind={}（content={}）", item.kind, item.content)
                }
            }
            log.info("退出时记忆整理：候选 {} 条 → 写入 {} 条", candidates.size, written)
            if (written > 0) {
                actionLog.append("整理记忆 $written 条", conversationId = conversationId)
                // 窗口行打标「已记忆」：本轮已整理过这段对话，下次退出整理
                // 看到标记不再改写复读（写入判重是精确匹配，改写式重复拦不住）
                chatHistoryService.markWindowMemorized(conversationId, reflectHistoryCount)
            }
        } catch (e: Exception) {
            log.warn("退出时记忆整理失败（忽略，不影响返回消息列表）: {}", e.message)
        }
    }

    /**
     * 写入前关键词复核：候选的关键词查库 → 命中回喂智能体过滤一轮
     *
     * - 零命中直接放行（省一次 LLM 调用）
     * - **命中截断到最新 [maxReviewHits] 条**：关键词扩容（5~10 个/条）后
     *   实盘命中曾达 77 条，全量回喂就是把复核 prompt 撑爆——复核要的是
     *   「最近的相似记忆」，最新优先足够判重
     * - 复核返回 [org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectResult.degraded]
     *   （LLM 异常/解析失败）→ **保守放行**候选：复核环节故障绝不允许吞掉记忆，
     *   写入侧还有 [MemoryService] 精确判重兜底
     */
    private fun reviewWithKeywordSearch(
        candidates: List<MemoryReflectItem>,
        subject: MemorySubject
    ): List<MemoryReflectItem> {
        val hits = linkedSetOf<String>()
        for (item in candidates) {
            // 关键词缺失时退化用 content 整句查（等价包含匹配，无害）
            val query = item.keywords.joinToString(" ").ifBlank { item.content }
            if (query.isBlank()) continue
            // search 按写入时间倒序 → 插入序即最新在前，取满即止（截最新 N 条）
            memoryService.search(query, subject)
                .take((maxReviewHits - hits.size).coerceAtLeast(0))
                .forEach { hits += it.content }
            if (hits.size >= maxReviewHits) break
        }
        if (hits.isEmpty()) return candidates
        val topHits = hits.take(maxReviewHits)
        val reviewed = memoryAgent.processObject(
            MemoryReflectRequest(candidates = candidates, matchedMemories = topHits)
        )
        if (reviewed.degraded) {
            log.warn("记忆关键词复核失败（保守放行 {} 条候选）", candidates.size)
            return candidates
        }
        log.debug("关键词复核：候选 {} 条 → 保留 {} 条（命中 {} 条，截取最新 {}）",
            candidates.size, reviewed.memories.size, hits.size, topHits.size)
        return reviewed.memories
    }

    companion object {
        /** 默认执行器：单线程 daemon——整理串行、不阻塞主循环、不挡 JVM 退出 */
        fun asyncExecutor(): Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "lucatbot-memory-reflect").apply { isDaemon = true }
        }
    }
}

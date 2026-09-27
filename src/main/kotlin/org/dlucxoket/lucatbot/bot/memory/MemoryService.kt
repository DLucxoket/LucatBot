package org.dlucxoket.lucatbot.bot.memory

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 记忆服务
 *
 * **模拟真人的记忆能力**：
 * - 记住每个人的特点、喜好、说过的话
 * - 对人有主观看法（"这人很有趣"）
 * - 跨会话记住（群聊→私聊都记得）
 * - 联系不同的人（"他和XX是朋友"）
 * - 记忆会衰减（越久越模糊）
 *
 * **OOP 设计**：
 * - 依赖 [MemoryRepository] 接口（DIP）
 * - 单一职责：记忆的存取、检索、维护
 * - 对外暴露语义化 API，不暴露存储细节
 */
@Component
class MemoryService(
    private val repository: MemoryRepository = JsonFileMemoryRepository()
) {
    private val log = LoggerFactory.getLogger(MemoryService::class.java)

    /**
     * 记住一个事实
     *
     * @param userId 关联的用户
     * @param content 事实描述（自然语言）
     * @param tags 标签（用于检索）
     */
    fun rememberFact(userId: Long, content: String, tags: List<String> = emptyList()) {
        repository.save(
            MemoryItem(
                userId = userId,
                type = MemoryItem.MemoryType.FACT,
                content = content,
                tags = tags
            )
        )
        log.info("记住事实: user=$userId, content=$content")
    }

    /**
     * 记住一个看法/印象
     *
     * @param userId 关联的用户
     * @param opinion 看法描述
     */
    fun rememberOpinion(userId: Long, opinion: String) {
        repository.save(
            MemoryItem(
                userId = userId,
                type = MemoryItem.MemoryType.OPINION,
                content = opinion
            )
        )
        log.info("记住看法: user=$userId, opinion=$opinion")
    }

    /**
     * 记住一个事件
     *
     * @param userId 关联的用户
     * @param event 事件描述
     * @param relatedUserIds 涉及的其他用户
     */
    fun rememberEvent(userId: Long, event: String, relatedUserIds: List<Long> = emptyList()) {
        repository.save(
            MemoryItem(
                userId = userId,
                type = MemoryItem.MemoryType.EVENT,
                content = event,
                relatedUserIds = relatedUserIds
            )
        )
        log.info("记住事件: user=$userId, event=$event")
    }

    /**
     * 记住人际关系
     *
     * @param userId 主用户
     * @param relatedUserId 关联用户
     * @param relation 关系描述
     */
    fun rememberRelation(userId: Long, relatedUserId: Long, relation: String) {
        repository.save(
            MemoryItem(
                userId = userId,
                type = MemoryItem.MemoryType.RELATION,
                content = relation,
                relatedUserIds = listOf(relatedUserId)
            )
        )
        log.info("记住关系: $userId ↔ $relatedUserId, $relation")
    }

    /**
     * 获取某个用户的全部记忆（按强度排序）
     */
    fun getMemoriesOf(userId: Long): List<MemoryItem> {
        return repository.findByUserId(userId)
            .sortedByDescending { it.strength }
    }

    /**
     * 获取某个用户的记忆摘要（注入 LLM prompt）
     *
     * 模拟真人回忆："我记得这个人..."
     */
    fun getMemorySummary(userId: Long): String {
        val memories = getMemoriesOf(userId)
        if (memories.isEmpty()) return ""

        val facts = memories.filter { it.type == MemoryItem.MemoryType.FACT }
        val opinions = memories.filter { it.type == MemoryItem.MemoryType.OPINION }
        val events = memories.filter { it.type == MemoryItem.MemoryType.EVENT }
        val relations = memories.filter { it.type == MemoryItem.MemoryType.RELATION }

        val sb = StringBuilder()
        sb.appendLine("关于此人的记忆：")

        if (facts.isNotEmpty()) {
            sb.appendLine("事实：")
            facts.take(5).forEach { sb.appendLine("- ${it.content}") }
        }
        if (opinions.isNotEmpty()) {
            sb.appendLine("看法：")
            opinions.take(3).forEach { sb.appendLine("- ${it.content}") }
        }
        if (events.isNotEmpty()) {
            sb.appendLine("经历：")
            events.take(5).forEach { sb.appendLine("- ${it.content}") }
        }
        if (relations.isNotEmpty()) {
            sb.appendLine("人际关系：")
            relations.take(3).forEach { sb.appendLine("- ${it.content}") }
        }

        return sb.toString().trim()
    }

    /**
     * 搜索相关记忆
     *
     * @param query 搜索关键词
     * @param userId 可选：限定某个用户
     * @return 相关记忆列表
     */
    fun search(query: String, userId: Long? = null): List<MemoryItem> {
        val keywords = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        val pool = if (userId != null) repository.findByUserId(userId) else repository.findAll()

        return pool.filter { item ->
            keywords.any { keyword ->
                item.content.contains(keyword, ignoreCase = true) ||
                    item.tags.any { it.contains(keyword, ignoreCase = true) }
            }
        }.sortedByDescending { it.strength }
    }

    /**
     * 获取与某用户相关的其他人（人际关系网络）
     */
    fun getRelatedUsers(userId: Long): Map<Long, String> {
        return repository.findByUserId(userId)
            .filter { it.type == MemoryItem.MemoryType.RELATION }
            .flatMap { item ->
                item.relatedUserIds.map { it to item.content }
            }
            .toMap()
    }

    /**
     * 记忆衰减（定期调用）
     *
     * 模拟真人记忆随时间模糊。
     */
    fun decayAll() {
        val all = repository.findAll()
        val decayed = all.map { it.copy(strength = (it.strength * 0.99).coerceAtLeast(0.1)) }
        repository.saveAll(decayed)
    }

    /**
     * 批量保存记忆
     */
    fun saveAll(memories: List<MemoryItem>) {
        repository.saveAll(memories)
    }

    /**
     * 持久化到文件
     */
    fun flush() {
        repository.flush()
    }
}

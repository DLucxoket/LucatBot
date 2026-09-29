package org.dlucxoket.lucatbot.bot.memory

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 记忆服务
 *
 * **模拟真人的记忆能力**：
 * - 记住每个人的特点、喜好、说过的话
 * - 对人有主观看法（"这人很有趣"）
 * - 跨会话记住（群聊→私聊都记得，键空间按 [MemorySubject] 隔离）
 *
 * 曾规划的衰减（decayAll）、人际关系写入（rememberRelation）等
 * 长期零调用已删（YAGNI，git 可找回）；[MemoryItem.strength] 仅作
 * 排序权重保留。
 *
 * **OOP 设计**：
 * - 依赖 [MemoryRepository] 接口（DIP）
 * - 单一职责：记忆的存取、检索、维护
 * - 对外暴露语义化 API，不暴露存储细节
 */
@Component
class MemoryService(
    private val repository: MemoryRepository
) {
    private val log = LoggerFactory.getLogger(MemoryService::class.java)

    /**
     * 记住一个事实
     *
     * @param subject 记忆主体（个人 / 群，键空间隔离）
     * @param content 事实描述（自然语言）
     * @param tags 标签（用于检索）
     */
    fun rememberFact(subject: MemorySubject, content: String, tags: List<String> = emptyList()) {
        if (isDuplicate(subject, content)) return
        repository.save(
            MemoryItem(
                userId = subject.id,
                subjectType = subject.type,
                type = MemoryItem.MemoryType.FACT,
                content = content,
                tags = tags
            )
        )
        log.info("记住事实: subject=$subject, content=$content")
    }

    /**
     * 记住一个看法/印象
     *
     * @param subject 记忆主体
     * @param opinion 看法描述
     */
    fun rememberOpinion(subject: MemorySubject, opinion: String) {
        if (isDuplicate(subject, opinion)) return
        repository.save(
            MemoryItem(
                userId = subject.id,
                subjectType = subject.type,
                type = MemoryItem.MemoryType.OPINION,
                content = opinion
            )
        )
        log.info("记住看法: subject=$subject, opinion=$opinion")
    }

    /**
     * 记住一个事件
     *
     * @param subject 记忆主体
     * @param event 事件描述
     * @param relatedUserIds 涉及的其他用户
     */
    fun rememberEvent(subject: MemorySubject, event: String, relatedUserIds: List<Long> = emptyList()) {
        if (isDuplicate(subject, event)) return
        repository.save(
            MemoryItem(
                userId = subject.id,
                subjectType = subject.type,
                type = MemoryItem.MemoryType.EVENT,
                content = event,
                relatedUserIds = relatedUserIds
            )
        )
        log.info("记住事件: subject=$subject, event=$event")
    }

    /**
     * 写入前判重——防「记忆被反复记录」
     *
     * 同一主体下已存在**同内容**记忆（归一化：去全部空白 + 忽略大小写）即跳过。
     * **不区分 kind**：同内容换个类型（fact/event）写两遍同样是重复。
     *
     * 背景：三个写入口都会重复触发——MemoryExtractor 每次 send 都对
     * 「对方最后一条消息」重新提取（同一句被反复正则命中）、决策 remember
     * 可多轮输出同一句、退出整理的 LLM 未必严格去重——判重统一收口在这里。
     *
     * 不做模糊/语义去重：那会误伤「原名 xx」这类更新式表述；改写型重复
     * 由 MemoryAgent 的 prompt 约束 + 现有记忆注入兜底。
     */
    private fun isDuplicate(subject: MemorySubject, content: String): Boolean {
        val target = normalize(content)
        if (target.isEmpty()) return false
        val dup = repository.findByUserId(subject.id).any {
            it.subjectType == subject.type && normalize(it.content) == target
        }
        if (dup) log.debug("记忆已存在，跳过写入: subject=$subject, content=$content")
        return dup
    }

    /** 判重归一化：去掉全部空白 + 小写（「他喜欢 猫」与「他喜欢猫」视为同一条） */
    private fun normalize(content: String): String =
        content.filter { !it.isWhitespace() }.lowercase()

    /**
     * 获取某个主体的全部记忆（按强度排序）
     *
     * 仓库按数值 ID 粗查后，这里按 [MemorySubject.Type] 精确过滤——
     * 群号与个人 QQ 号即使相同也不会互相泄漏。
     */
    fun getMemoriesOf(subject: MemorySubject): List<MemoryItem> {
        return repository.findByUserId(subject.id)
            .filter { it.subjectType == subject.type }
            .sortedByDescending { it.strength }
    }

    /**
     * 获取某个主体的记忆摘要（注入 LLM prompt）
     *
     * 模拟真人回忆："我记得这个人..."
     *
     * **每行带写入日期**（记于MM-DD，跨年带年份）——无日期的旧记忆会被
     * LLM 当成刚发生的事（实盘出过：昨天的对话被当成当前剧情去接话）。
     *
     * @param factLimit/opinionLimit/eventLimit/relationLimit 各类条数上限——
     *   会话注入用默认值（控制 prompt 体积）；**退出整理判重时调大**
     *   （看不见已有记忆 → LLM 会把同一件事再记一遍）
     */
    fun getMemorySummary(
        subject: MemorySubject,
        factLimit: Int = 5,
        opinionLimit: Int = 3,
        eventLimit: Int = 5,
        relationLimit: Int = 3
    ): String {
        val memories = getMemoriesOf(subject)
        if (memories.isEmpty()) return ""

        val facts = memories.filter { it.type == MemoryItem.MemoryType.FACT }
        val opinions = memories.filter { it.type == MemoryItem.MemoryType.OPINION }
        val events = memories.filter { it.type == MemoryItem.MemoryType.EVENT }
        val relations = memories.filter { it.type == MemoryItem.MemoryType.RELATION }

        val sb = StringBuilder()
        sb.appendLine("关于此人的记忆：")

        if (facts.isNotEmpty()) {
            sb.appendLine("事实：")
            facts.take(factLimit).forEach { sb.appendLine("- ${it.content}（记于${datedOn(it.createdAt)}）") }
        }
        if (opinions.isNotEmpty()) {
            sb.appendLine("看法：")
            opinions.take(opinionLimit).forEach { sb.appendLine("- ${it.content}（记于${datedOn(it.createdAt)}）") }
        }
        if (events.isNotEmpty()) {
            sb.appendLine("经历：")
            events.take(eventLimit).forEach { sb.appendLine("- ${it.content}（记于${datedOn(it.createdAt)}）") }
        }
        if (relations.isNotEmpty()) {
            sb.appendLine("人际关系：")
            relations.take(relationLimit).forEach { sb.appendLine("- ${it.content}（记于${datedOn(it.createdAt)}）") }
        }

        return sb.toString().trim()
    }

    /** 记忆写入日期（MMdd；跨年带年份）——摘要行尾的「（记于…）」 */
    private fun datedOn(epochMs: Long): String {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val date = java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val pattern = if (date.year == java.time.LocalDate.now(zone).year) "MM-dd" else "yyyy-MM-dd"
        return date.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    }

    /**
     * 搜索相关记忆
     *
     * @param query 搜索关键词
     * @param subject 可选：限定某个记忆主体（null = 全部）
     * @return 相关记忆列表
     */
    fun search(query: String, subject: MemorySubject? = null): List<MemoryItem> {
        val keywords = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        val pool = if (subject != null) getMemoriesOf(subject) else repository.findAll()

        return pool.filter { item ->
            keywords.any { keyword ->
                item.content.contains(keyword, ignoreCase = true) ||
                    item.tags.any { it.contains(keyword, ignoreCase = true) }
            }
        }.sortedByDescending { it.strength }
    }

    /**
     * 批量保存记忆——与单条入口同一套判重
     *
     * 过滤两类重复：批内互重（同一句提取出多条）+ 与已有记忆重复
     * （MemoryExtractor 每次 send 都对同一句重新提取，不过这里必反复记录）。
     */
    fun saveAll(memories: List<MemoryItem>) {
        val batchKeys = mutableSetOf<String>()
        val fresh = memories.filter { item ->
            val key = "${item.subjectType}|${item.userId}|${normalize(item.content)}"
            when {
                item.content.isBlank() -> false
                !batchKeys.add(key) -> false  // 批内重复
                else -> !isDuplicate(MemorySubject(item.subjectType, item.userId), item.content)
            }
        }
        if (fresh.isEmpty()) return
        repository.saveAll(fresh)
    }

    /**
     * 持久化到文件
     */
    fun flush() {
        repository.flush()
    }
}

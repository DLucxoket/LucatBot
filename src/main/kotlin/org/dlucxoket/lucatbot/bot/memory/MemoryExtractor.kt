package org.dlucxoket.lucatbot.bot.memory

/**
 * 记忆提取器
 *
 * **模拟真人的记忆形成**：从对话中自然地提取关键信息。
 *
 * 策略模式（OCP）：新增提取规则只需添加新的 [ExtractionStrategy]，不改已有代码。
 */
object MemoryExtractor {

    /**
     * 提取策略接口
     */
    interface ExtractionStrategy {
        /** 从用户消息中提取记忆内容，无则返回 null */
        fun extract(subject: MemorySubject, userMessage: String): MemoryItem?
    }

    /** 名字提取 */
    private val nameStrategy = object : ExtractionStrategy {
        private val regex = Regex("我叫(\\S+)")
        override fun extract(subject: MemorySubject, userMessage: String): MemoryItem? {
            val match = regex.find(userMessage) ?: return null
            return MemoryItem(
                userId = subject.id,
                subjectType = subject.type,
                type = MemoryItem.MemoryType.FACT,
                content = "他叫${match.groupValues[1]}",
                tags = listOf("名字")
            )
        }
    }

    /** 喜好提取 */
    private val likeStrategy = object : ExtractionStrategy {
        private val regex = Regex("我(喜欢|爱)(\\S+)")
        override fun extract(subject: MemorySubject, userMessage: String): MemoryItem? {
            val match = regex.find(userMessage) ?: return null
            return MemoryItem(
                userId = subject.id,
                subjectType = subject.type,
                type = MemoryItem.MemoryType.FACT,
                content = "他喜欢${match.groupValues[2]}",
                tags = listOf("喜好")
            )
        }
    }

    /** 所有提取策略 */
    private val strategies: List<ExtractionStrategy> = listOf(nameStrategy, likeStrategy)

    /**
     * 从对话中提取记忆
     *
     * 只在命中提取策略（名字、喜好等）时产出记忆；
     * **不再**把每条消息都存成 EVENT——那会把记忆文件刷成聊天记录副本。
     *
     * **调用方责任**：只能把**用户**的消息传进来——机器人自己的发言
     * （如「我喜欢摸鱼」）若被传入，会被正则误记成该主体的长期 FACT。
     *
     * @param subject 记忆主体（个人 QQ / 群，键空间隔离）
     * @param userMessage **用户**的消息（不含机器人自己的发言）
     * @return 提取到的记忆列表（可能为空）
     */
    fun extract(subject: MemorySubject, userMessage: String): List<MemoryItem> {
        if (userMessage.isBlank()) return emptyList()
        val memories = mutableListOf<MemoryItem>()

        // 应用所有提取策略
        strategies.forEach { strategy ->
            strategy.extract(subject, userMessage)?.let { memories.add(it) }
        }

        return memories
    }
}

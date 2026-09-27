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
        fun extract(userId: Long, userMessage: String): MemoryItem?
    }

    /** 名字提取 */
    private val nameStrategy = object : ExtractionStrategy {
        private val regex = Regex("我叫(\\S+)")
        override fun extract(userId: Long, userMessage: String): MemoryItem? {
            val match = regex.find(userMessage) ?: return null
            return MemoryItem(
                userId = userId,
                type = MemoryItem.MemoryType.FACT,
                content = "他叫${match.groupValues[1]}",
                tags = listOf("名字")
            )
        }
    }

    /** 喜好提取 */
    private val likeStrategy = object : ExtractionStrategy {
        private val regex = Regex("我(喜欢|爱)(\\S+)")
        override fun extract(userId: Long, userMessage: String): MemoryItem? {
            val match = regex.find(userMessage) ?: return null
            return MemoryItem(
                userId = userId,
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
     * @param userId 用户 QQ 号
     * @param userMessage 用户消息
     * @param botReply 机器人回复
     * @return 提取到的记忆列表
     */
    fun extract(userId: Long, userMessage: String, botReply: String): List<MemoryItem> {
        val memories = mutableListOf<MemoryItem>()

        // 总是记录对话事件
        memories.add(
            MemoryItem(
                userId = userId,
                type = MemoryItem.MemoryType.EVENT,
                content = "对我说了：$userMessage"
            )
        )

        // 应用所有提取策略
        strategies.forEach { strategy ->
            strategy.extract(userId, userMessage)?.let { memories.add(it) }
        }

        return memories
    }
}

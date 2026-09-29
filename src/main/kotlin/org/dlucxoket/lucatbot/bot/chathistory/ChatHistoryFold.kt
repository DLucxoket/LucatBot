package org.dlucxoket.lucatbot.bot.chathistory

/**
 * 物理消息折叠——实时入档与爬楼入档孪生行的判定与合并（纯函数、单一来源）
 *
 * 同一条消息会被两种路径入档：实时事件/发送回执（带 message_id → 稳定 id
 * `msg:<会话>:<id>`）与爬楼（旧式 `hist:` id；新爬楼经自定义 DTO 拿到 id 后
 * 也是 `msg:`）。id 相同的行由仓库 upsert 天然合一；**id 不同**的孪生行
 * （存量 hist:/UUID 行、无回执的 UUID 行）靠本对象的 [fold] 合并：
 *
 * - **读取时 fold**：[org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService.getHistory]
 *   注入 prompt 前折叠（历史存量立即对 LLM 生效）
 * - **存储层 fold**：启动清理（`collapseStoredTwins`）与迁移源 json 折叠复用同一套规则，
 *   逐步把存储收敛为「一条物理消息一行、id 以 `msg:` 为准」
 *
 * 判定规则（[isTwin]）：同发送者 + 10s 窗口 + 归一化内容相同 +
 * **至少一方是 `hist:` 爬楼行，或恰一方是 `msg:` 行另一方是无回执 UUID**——
 * 两个 `msg:` 不同行 = 不同消息；两个 UUID 行 = 真实连发，均不折叠。
 *
 * 合并规则（[fold]）：内容取更干净的（不含 CQ 码），id 取权威度更高的
 * （`msg:` > 无前缀 UUID > `hist:`），messageId 取任一非空。
 */
object ChatHistoryFold {

    /** 同一物理消息的时间容差（实时入档用本地时钟，爬楼用服务端秒级时间戳） */
    const val DEFAULT_WINDOW_MS = 10_000L

    /** CQ 码段（[CQ:at,qq=1] 等），比较内容前剥掉 */
    private val cqCodeRegex = Regex("""\[CQ:[^\]]*]""")

    /** describe 富占位符（[图片]/[表情:14]/@昵称(QQ)/回复 #id：等），比较内容前剥掉 */
    private val richPlaceholderRegex = Regex(
        """\[图片[^\]]*]|\[表情:[^\]]*]|\[表情[^\]]*]|@\S+\(\d+\)|@\d+|回复\s*#\d+：|回复\s*@\S+\(\d+\)："""
    )

    /** 比较用归一化：剥 CQ 码与富占位符后压缩首尾 */
    fun normalize(content: String): String =
        content.replace(cqCodeRegex, "").replace(richPlaceholderRegex, "").trim()

    /**
     * id 权威度：`msg:`（带 message_id，引用解析可靠）> 无前缀 UUID > `hist:`（爬楼兜底）。
     * 折叠时保权威 id，让存储逐步收敛为「一条物理消息一行、id 以 msg: 为准」。
     */
    fun idRank(id: String): Int = when {
        id.startsWith("msg:") -> 2
        id.startsWith("hist:") -> 0
        else -> 1
    }

    /** bot 记录 senderId 不一致（实时 0 / 爬楼 selfId），用 isFromBot 区分即可 */
    fun sameSender(a: ChatMessageRecord, b: ChatMessageRecord): Boolean =
        a.isFromBot == b.isFromBot && (a.isFromBot || a.senderId == b.senderId)

    /** 两条记录是否同一物理消息的孪生行（见类 KDoc 的判定规则） */
    fun isTwin(a: ChatMessageRecord, b: ChatMessageRecord, windowMs: Long = DEFAULT_WINDOW_MS): Boolean {
        if (!sameSender(a, b)) return false
        if (kotlin.math.abs(a.timestamp - b.timestamp) > windowMs) return false
        if (normalize(a.content) != normalize(b.content)) return false
        // 孪生 = 至少一方是爬楼行（hist:），或恰一方是权威 msg: 行、另一方是无回执 UUID——
        // 两个 msg: 行 id 不同 = 不同消息；两个 UUID = 真实连发，均不折叠
        val aMsg = a.id.startsWith("msg:")
        val bMsg = b.id.startsWith("msg:")
        return a.id.startsWith("hist:") || b.id.startsWith("hist:") || aMsg != bMsg
    }

    /** 折叠结果：保留行（已合并内容/id/messageId）+ 被并掉的原始 id */
    data class Result(
        val kept: List<ChatMessageRecord>,
        val droppedIds: List<String>
    )

    /**
     * 按时间序折叠孪生行
     *
     * - 内容：取不含 CQ 码的干净版本（对 prompt 友好）
     * - id：取权威度更高者（见 [idRank]；平手取后来者）
     * - messageId：取任一非空（爬楼旧式行没有，被引用解析靠它）
     * - 位置：合并结果保留在首次出现的位置（时间序不变）
     */
    fun fold(records: List<ChatMessageRecord>, windowMs: Long = DEFAULT_WINDOW_MS): Result {
        val kept = mutableListOf<ChatMessageRecord>()
        val dropped = linkedSetOf<String>()
        for (record in records.sortedBy { it.timestamp }) {
            val idx = kept.indexOfFirst { isTwin(it, record, windowMs) }
            if (idx < 0) {
                kept += record
                continue
            }
            val survivor = kept[idx]
            val contentSource =
                if (survivor.content.contains("[CQ:") && !record.content.contains("[CQ:")) record else survivor
            val idSource = if (idRank(record.id) >= idRank(survivor.id)) record else survivor
            val merged = contentSource.copy(
                id = idSource.id,
                messageId = contentSource.messageId ?: survivor.messageId ?: record.messageId,
                imageUrls = contentSource.imageUrls ?: survivor.imageUrls ?: record.imageUrls,
                // 已记忆标记任一行为真即真（孪生行可能一条被打标一条没有）
                memorized = contentSource.memorized || survivor.memorized || record.memorized
            )
            listOf(survivor.id, record.id)
                .filter { it != merged.id && it !in dropped }
                .forEach { dropped += it }
            kept[idx] = merged
        }
        return Result(kept, dropped.toList())
    }
}

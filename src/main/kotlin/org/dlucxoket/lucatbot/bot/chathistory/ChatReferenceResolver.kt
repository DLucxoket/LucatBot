package org.dlucxoket.lucatbot.bot.chathistory

/**
 * 引用解析器——把富文本里的 `@QQ` / `回复 #消息id` 解析成 LLM 能理解的指向
 *
 * 背景：`ChatMessage.describe()` 输出的 `@1923043992`、`回复 #1683970346：`
 * 对 LLM 是无意义的裸编号——它不知道在艾特谁、回复的是哪句话。本类在
 * **渲染 prompt 前**用当前聊天窗口的记录做一次纯文本改写：
 *
 * - `@123456789` → `@小明(123456789)`（该 QQ 在本窗口说过话才有昵称；否则保持原样）
 * - `回复 #1683970346：xxx` → `回复 @小明[14:02]：被回复内容摘录`
 *   （窗口内查不到该 message_id 时保持 `#id` 原样——id 未入档的老消息无从解析）
 *
 * 设计要点：
 * - **纯函数、无 IO**：索引由调用方用窗口记录构建，时间格式由调用方注入
 *   （低耦合：不依赖 Tool/Service/Controller 任何一方）
 * - 只改写、不丢信息：解析失败一律原样保留，LLM 至少知道「在回复某条消息」
 * - 剥占位符后再摘录，避免回复内容里嵌套 `[图片]` 等标记干扰
 */
object ChatReferenceResolver {

    /** 窗口内一条消息（建索引用） */
    data class WindowEntry(
        val messageId: Long?,
        val senderId: Long,
        val senderName: String,
        val timestamp: Long,
        val content: String
    )

    /** 窗口索引：message_id → 消息；QQ → 昵称 */
    class Index internal constructor(
        private val byMessageId: Map<Long, WindowEntry>,
        private val nameByQq: Map<Long, String>
    ) {
        internal fun messageOf(id: Long): WindowEntry? = byMessageId[id]
        internal fun nameOf(qq: Long): String? = nameByQq[qq]
    }

    /** `回复 #123：`（冒号可有可无、全角半角） */
    private val replyRegex = Regex("""回复\s*#(\d+)\s*[:：]?""")

    /** 裸 `@123456`（后面不是 `(`，避免重复改写已带昵称的） */
    private val atRegex = Regex("""@(\d{5,})(?!\()""")

    /** describe 富占位符（摘录前剥掉） */
    private val markerRegex = Regex(
        """\[图片[^\]]*]|\[表情:[^\]]*]|\[表情[^\]]*]|\[表情包[^\]]*]"""
    )

    /** 摘录长度上限（字符） */
    private const val BRIEF_MAX = 30

    /** 用窗口记录构建索引 */
    fun indexOf(entries: List<WindowEntry>): Index {
        val byMessageId = entries.filter { it.messageId != null }
            .associateBy { it.messageId!! }
        val nameByQq = entries.filter { it.senderId > 0L && it.senderName.isNotBlank() }
            .associate { it.senderId to it.senderName }
        return Index(byMessageId, nameByQq)
    }

    /**
     * 改写一条消息内容里的引用
     *
     * @param content describe 风格的富文本
     * @param index 窗口索引
     * @param formatTime 时间格式化（调用方注入，与聊天记录行保持一致）
     */
    fun resolve(content: String, index: Index, formatTime: (Long) -> String): String {
        if (content.isEmpty()) return content

        val withReply = replyRegex.replace(content) { m ->
            val ref = index.messageOf(m.groupValues[1].toLong())
            if (ref == null) {
                m.value  // 查不到：保留原样（#id 仍有"回复了某条"的语义）
            } else {
                "回复 @${ref.senderName}[${formatTime(ref.timestamp)}]：${brief(ref.content)}"
            }
        }
        return atRegex.replace(withReply) { m ->
            val qq = m.groupValues[1].toLong()
            val name = index.nameOf(qq)
            if (name == null) m.value else "@$name($qq)"
        }
    }

    /** 被回复内容的单行摘录：剥占位符 → 压缩空白 → 截断 */
    private fun brief(content: String): String {
        val plain = content.replace(markerRegex, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (plain.length <= BRIEF_MAX) plain else plain.take(BRIEF_MAX) + "…"
    }
}

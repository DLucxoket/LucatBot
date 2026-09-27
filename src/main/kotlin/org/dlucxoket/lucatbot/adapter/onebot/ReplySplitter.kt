package org.dlucxoket.lucatbot.adapter.onebot

/**
 * 回复拆分器
 *
 * **模拟真人的打字习惯**：不发一大段，拆成几条短消息。
 *
 * 单一职责：只做回复拆分，不关心发送/延迟/其他逻辑。
 */
object ReplySplitter {

    /** 每条最大长度 */
    private const val MAX_LENGTH = 30

    /** 最多拆分条数 */
    private const val MAX_MESSAGES = 3

    /** 短回复不拆的阈值 */
    private const val SHORT_THRESHOLD = 15

    /**
     * 拆分回复为多条短消息
     *
     * - 按句号/换行断开
     * - 合并短句为不超过 [MAX_LENGTH] 字的段落
     * - 最多 [MAX_MESSAGES] 条
     */
    fun split(reply: String): List<String> {
        if (reply.length <= SHORT_THRESHOLD) return listOf(reply)

        val sentences = reply.split(Regex("(?<=[。！？!?\\n])"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.length + sentence.length > MAX_LENGTH && current.isNotEmpty()) {
                result.add(current.toString().trim())
                current.clear()
            }
            current.append(sentence)
        }
        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }

        // 最多 MAX_MESSAGES 条，多余的合并到最后一条
        return if (result.size > MAX_MESSAGES) {
            result.take(MAX_MESSAGES - 1) + result.drop(MAX_MESSAGES - 1).joinToString("")
        } else {
            result
        }
    }
}

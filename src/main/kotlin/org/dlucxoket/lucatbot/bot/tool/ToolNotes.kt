package org.dlucxoket.lucatbot.bot.tool

/**
 * 工具上下文笔记（toolNotes）——编排层维护的「工具使用旁证」
 *
 * 智能体每次 `list_tools` / `tool` 之后，由本类把目录/执行结果攒起来，
 * 随下一轮 prompt 注入（格式化与「同会话只保留一份目录」的去重契约
 * **只存在于本类**——两个 Controller 不再各写一份字符串逻辑）。
 *
 * 同时承担两层护栏（防病态值的资源护栏，与 stay 钳制同性质）：
 * - **字符预算**：[maxTotalChars] 超限时优先淘汰最旧的执行结果，目录常驻
 * - **连续步数硬上限**：[hardLimitSteps] 个连续工具步后 [exceeded] 为 true，
 *   编排层据此强制收尾；配合 [snapshot] 首行的状态计数（软提示）让
 *   LLM 在 prompt 里就能看到「已经连续查了几轮工具」
 *
 * 非工具决策（send/return/open/idle…）后由编排层调 [resetStreak] 归零。
 * 仅在主循环单线程内使用，无并发写。
 */
class ToolNotes(
    /** 目录 + 执行结果的总字符预算（超出淘汰最旧结果） */
    private val maxTotalChars: Int = 16_000,
    /** 连续工具步硬上限（超过则编排层强制收尾） */
    private val hardLimitSteps: Int = 12
) {
    private val entries = mutableListOf<String>()
    private var catalogCount = 0
    private var callCount = 0
    private var consecutiveSteps = 0

    /** 连续工具步数（自上次非工具决策以来） */
    val steps: Int get() = consecutiveSteps

    /** 是否已达硬上限（编排层据此强制收尾，防无限烧 token） */
    fun exceeded(): Boolean = consecutiveSteps >= hardLimitSteps

    /** 非工具决策后重置连续计数 */
    fun resetStreak() {
        consecutiveSteps = 0
    }

    /** 记录一次目录获取：同时只保留一份目录（重复获取覆盖旧条目） */
    fun recordCatalog(query: String?, catalogText: String) {
        entries.removeAll { it.startsWith(CATALOG_PREFIX) }
        entries += "$CATALOG_HEADER${query?.let { "搜索「$it」" } ?: "全部"}）\n$catalogText"
        catalogCount++
        consecutiveSteps++
        trimToBudget()
    }

    /** 记录一次工具执行结果 */
    fun recordResult(toolName: String, resultJson: String) {
        entries += "（工具 $toolName 执行结果）\n$resultJson"
        callCount++
        consecutiveSteps++
        trimToBudget()
    }

    /**
     * 注入 prompt 的快照：状态计数行 + 目录 + 执行结果
     *
     * 状态行放首行（软提示）：连续工具步 ≥3 时附加「请尽快正常决策」的点名。
     */
    fun snapshot(): List<String> {
        val status = buildString {
            append("（工具状态：已查目录 $catalogCount 次、已调用工具 $callCount 次、连续工具步 $consecutiveSteps")
            if (consecutiveSteps >= 3) append("——已连续查工具多轮，请尽快给出正常决策，不要再查工具")
            append("）")
        }
        return listOf(status) + entries.toList()
    }

    private fun trimToBudget() {
        while (entries.sumOf { it.length } > maxTotalChars) {
            // 目录常驻（至多一份），优先淘汰最旧的执行结果
            val oldestResult = entries.indexOfFirst { !it.startsWith(CATALOG_PREFIX) }
            if (oldestResult < 0) break  // 只剩目录也超预算：豁免保留（单份目录有界）
            entries.removeAt(oldestResult)
        }
    }

    private companion object {
        /** 目录条目的去重契约前缀（内部单一事实源） */
        const val CATALOG_PREFIX = "（工具目录"
        const val CATALOG_HEADER = "（工具目录"
    }
}

package org.dlucxoket.lucatbot.bot.tool

/**
 * 工具上下文笔记（toolNotes）——编排层维护的「工具使用旁证」
 *
 * 智能体每次 `list_tools` / `tool` 之后，由本类把目录/执行结果攒起来，
 * 随下一轮 prompt 注入（格式化与「同会话只保留一份目录」的去重契约
 * **只存在于本类**——两个 Controller 不再各写一份字符串逻辑）。
 *
 * 护栏语义（2026-10-01 实盘后调整：**不再限制正常工具轮数**）：
 * - **字符预算**：[maxTotalChars] 超限时优先淘汰最旧的执行结果，目录常驻
 * - **只限连续调用错误**：[maxConsecutiveErrors] 个**连续失败**的工具调用后
 *   [errorsExceeded] 为 true，编排层据此强制收尾——多轮成功的 list_tools /
 *   tool 不再触发任何限制（实盘第 7 轮被「连续步 ≥3」的软提示逼停正常排查，
 *   而真正该停的场景是反复报错空转）。成功一次即清零错误连击，
 *   语义是「连续」而非「累计」。
 *
 * 非工具决策（send/return/open/idle…）后由编排层调 [resetStreak] 归零。
 * 仅在主循环单线程内使用，无并发写。
 *
 * @param maxConsecutiveErrors 连续失败调用上限（达此数后 [errorsExceeded] 为 true；
 *   list_tools 不产生错误计数——它只有「有无匹配」，没有失败态）
 */
class ToolNotes(
    /**
     * 目录 + 执行结果的总字符预算（超出淘汰最旧结果）
     *
     * 16_000 → **10_000**（P1 token 精简）：目录常驻约 3k 字符（18 个工具的
     * 描述+参数实测 2970），余量留 1~2 条结果——单条结果本身也从 4_000 收到
     * 3_000（见 `ToolCatalog.maxResultChars`）。这段每轮都注入 user 消息，
     * 少 6k 字符 ≈ 每轮省 ~4k token；代价是第 3 条以后的结果更早淘汰、
     * 需要时再查一次（重查一轮的成本也不高于常驻 6k）
     */
    private val maxTotalChars: Int = 10_000,
    /** 连续**调用错误**硬上限（正常工具轮不限；成功一次即清零） */
    private val maxConsecutiveErrors: Int = 3
) {
    private val entries = mutableListOf<String>()
    private var catalogCount = 0
    private var callCount = 0
    private var consecutiveSteps = 0
    private var errorStreak = 0

    /** 连续工具步数（自上次非工具决策以来；仅状态展示，不再用于限制） */
    val steps: Int get() = consecutiveSteps

    /** 连续**失败**调用数（成功一次清零；list_tools 不计入） */
    val consecutiveErrors: Int get() = errorStreak

    /**
     * 是否已达连续错误上限（编排层据此强制收尾）
     *
     * 只看错误连击——多轮成功调用**不会**触发（旧版的连续步数硬上限已移除）。
     */
    fun errorsExceeded(): Boolean = errorStreak >= maxConsecutiveErrors

    /** 非工具决策后重置连续计数（步数与错误连击一起归零） */
    fun resetStreak() {
        consecutiveSteps = 0
        errorStreak = 0
    }

    /** 记录一次目录获取：同时只保留一份目录（重复获取覆盖旧条目） */
    fun recordCatalog(query: String?, catalogText: String) {
        entries.removeAll { it.startsWith(CATALOG_PREFIX) }
        entries += "$CATALOG_HEADER${query?.let { "搜索「$it」" } ?: "全部"}）\n$catalogText"
        catalogCount++
        consecutiveSteps++
        trimToBudget()
    }

    /**
     * 记录一次工具执行结果
     *
     * @param isError 本次调用是否失败（[org.dlucxoket.lucatbot.bot.tool.ToolCatalog]
     *   的结构化判定）——失败累加错误连击、成功清零；状态行据此点名
     */
    fun recordResult(toolName: String, resultJson: String, isError: Boolean = false) {
        entries += "（工具 $toolName 执行结果）\n$resultJson"
        callCount++
        consecutiveSteps++
        errorStreak = if (isError) errorStreak + 1 else 0
        trimToBudget()
    }

    /**
     * 注入 prompt 的快照：状态计数行 + 目录 + 执行结果
     *
     * 状态行放首行（可观测性）：报错时点名「检查参数/停止调用」，
     * 但**成功轮次不施加任何压力**（不再有「连续步 ≥3 请尽快正常决策」）。
     */
    fun snapshot(): List<String> {
        val status = buildString {
            append("（工具状态：已查目录 $catalogCount 次、已调用工具 $callCount 次、连续工具步 $consecutiveSteps、连续报错 $consecutiveErrors")
            if (consecutiveErrors > 0) {
                append("——上一次调用失败，检查错误信息修正参数后再试；连续报错 $maxConsecutiveErrors 次将强制停止工具调用")
            }
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

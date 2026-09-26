package org.dlucxoket.lucatbot.bot.agent

/**
 * DispatcherAgent 输出解析器
 *
 * 将 LLM 返回的调用计划文本解析为 [AgentPlan] 列表。
 *
 * 解析规则：
 * - 每行格式为 `AgentName: 输入内容`
 * - 空行忽略
 * - 整体输出为 `done`（忽略大小写和首尾空白）表示终止
 * - 格式异常的行跳过并记警告
 */
object AgentPlanParser {

    /**
     * 解析 LLM 输出的调用计划
     *
     * @param llmOutput DispatcherAgent 返回的原始文本
     * @return 调用计划列表；若输出为 `done` 则返回 null
     */
    fun parse(llmOutput: String): List<AgentPlan>? {
        val trimmed = llmOutput.trim()

        // done → 终止
        if (trimmed.equals("done", ignoreCase = true)) {
            return null
        }

        val plans = mutableListOf<AgentPlan>()
        for (line in trimmed.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue

            val separatorIndex = t.indexOf(':')
            if (separatorIndex <= 0 || separatorIndex == t.length - 1) {
                // 格式异常：缺少冒号、Agent 名为空、或冒号后无内容
                continue
            }

            val agentName = t.substring(0, separatorIndex).trim()
            val input = t.substring(separatorIndex + 1).trim()
            if (agentName.isEmpty() || input.isEmpty()) continue

            plans.add(AgentPlan(agentName, input))
        }

        return plans.ifEmpty { null }
    }
}

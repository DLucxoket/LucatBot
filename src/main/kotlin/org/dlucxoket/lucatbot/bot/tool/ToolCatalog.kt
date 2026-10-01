package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 工具目录与调用网关——主智能体 `list_tools` / `tool` 动作的执行层
 *
 * 把「LLM 选工具」接进固定脚本主循环：智能体先要目录（全量或按关键词
 * 搜索），拿到「名字 + 功能 + 何时使用 + 参数」后再发起调用；编排层经
 * 本类执行并把结果喂回下一轮 prompt（notes 的攒取与格式化在 [ToolNotes]）。
 *
 * 设计要点：
 * - **不调 LLM**：目录拼装与执行都是确定性操作（属 Tool 侧能力）
 * - 工具来源可注入（[toolsProvider]），测试用假工具即可，不必跑 ClassGraph
 * - **[orchestrationOnly] 排除策略**：编排层专属动作的实现（如
 *   SendMessageTool——发送必须走 send 动作，才能写记忆/破冰/连发记账）
 *   不进目录也不可被调用——策略在装配层（LoopConfig）配置，本类只执行
 * - [invoke] 捕获一切异常并转错误 JSON——某工具失败不能打断决策循环
 * - 执行走 [Tool.process]（JSON→OOP→JSON 模板），LLM 给的 input 原样过边界
 *
 * @param toolsProvider 工具来源（默认 ToolDiscovery 发现的单例列表）
 * @param maxResultChars 单次执行结果注入 prompt 的最大长度（防爆 context）
 * @param orchestrationOnly 目录与调用均排除的工具名（忽略大小写）
 */
class ToolCatalog(
    private val toolsProvider: () -> List<Tool<*, *>> = { ToolDiscovery.getTools() },
    private val maxResultChars: Int = 4_000,
    private val orchestrationOnly: Set<String> = emptySet()
) {
    private val mapper = jacksonObjectMapper()

    /** 对 LLM 可见（可调用）的工具 */
    private fun visibleTools(): List<Tool<*, *>> =
        toolsProvider().filter { tool ->
            orchestrationOnly.none { it.equals(tool.name, ignoreCase = true) }
        }

    /** 目录/搜索的共用过滤（query 空白 = 全部；按名字或描述模糊匹配，忽略大小写） */
    private fun matching(query: String?): List<Tool<*, *>> {
        val q = query?.trim().orEmpty()
        val all = visibleTools()
        if (q.isEmpty()) return all
        return all.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true)
        }
    }

    /**
     * 生成工具目录文本
     *
     * @param query 关键词：空白 = 全部工具；否则按名字或描述模糊匹配
     * @return Markdown 目录（每条含 名字/功能/何时使用/参数）；无匹配时给提示
     */
    fun catalog(query: String?): String {
        val q = query?.trim().orEmpty()
        val matched = matching(query)
        if (matched.isEmpty()) {
            val total = visibleTools().size
            return "（没有匹配「$q」的工具；用 list_tools 不带 query 可查看全部 $total 个工具）"
        }
        return matched.joinToString("\n") { tool ->
            buildString {
                appendLine("### ${tool.name}")
                appendLine(tool.description.trim())
                appendLine("参数：${tool.parameterHint.removePrefix("JSON:").removePrefix("json:").trim()}")
            }.trimEnd()
        }
    }

    /** 目录条目数（供操作日志） */
    fun count(query: String?): Int = matching(query).size

    /**
     * 可见工具名清单（**固定注入 prompt**：名字常驻，模型不必先 list_tools 才知道有啥）
     *
     * 只给名字——参数与「何时使用」仍需 list_tools 看目录
     */
    fun names(): List<String> = visibleTools().map { it.name }

    /**
     * 执行一次工具调用
     *
     * @param name 工具名（忽略大小写；目录中的名字）
     * @param input LLM 给的参数对象（Jackson 转 JSON 后走 Tool 的 JSON 边界）
     * @return 工具结果 JSON；工具不存在 / 编排专属 / 执行异常时返回错误 JSON
     */
    fun invoke(name: String, input: Map<String, Any?>): String {
        if (orchestrationOnly.any { it.equals(name, ignoreCase = true) }) {
            return errorJson("工具「$name」由编排层专属管理，不可经 tool 调用")
        }
        val tool = visibleTools().find { it.name.equals(name, ignoreCase = true) }
            ?: return errorJson("工具「$name」不存在；先用 list_tools 查看可用工具")
        return try {
            val inputJson = mapper.writeValueAsString(input)
            val resultJson = tool.process(inputJson)
            if (resultJson.length > maxResultChars) {
                resultJson.take(maxResultChars) + "…（结果超长已截断）"
            } else {
                resultJson
            }
        } catch (e: Exception) {
            errorJson("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 错误结果统一经 mapper 序列化（结构转义安全，不在业务里手拼 JSON） */
    private fun errorJson(message: String): String =
        mapper.writeValueAsString(mapOf("success" to false, "error" to message))
}

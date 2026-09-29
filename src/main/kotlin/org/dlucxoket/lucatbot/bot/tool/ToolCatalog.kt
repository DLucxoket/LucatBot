package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.dlucxoket.lucatbot.bot.agent.AgentDiscovery

/**
 * 工具目录与调用网关——主智能体 `list_tools` / `tool` 动作的执行层
 *
 * 把「LLM 选工具」接进固定脚本主循环：智能体先要目录（全量或按关键词
 * 搜索），拿到「名字 + 功能 + 何时使用 + 参数」后再发起调用；编排层经
 * 本类执行并把结果以系统注释形式喂回下一轮 prompt。
 *
 * 设计要点：
 * - **不调 LLM**：目录拼装与执行都是确定性操作（属 Tool 侧能力）
 * - 工具来源可注入（[toolsProvider]），测试用假工具即可，不必跑 ClassGraph
 * - [invoke] 捕获一切异常并转错误 JSON——某工具失败不能打断决策循环
 * - 执行走 [Tool.process]（JSON→OOP→JSON 模板），LLM 给的 input 原样过边界
 *
 * @param toolsProvider 工具来源（默认 ClassGraph 发现的单例索引）
 * @param maxResultChars 单次执行结果注入 prompt 的最大长度（防爆 context）
 */
class ToolCatalog(
    private val toolsProvider: () -> List<Tool<*, *>> = { AgentDiscovery.getTools() },
    private val maxResultChars: Int = 4_000
) {
    private val mapper = jacksonObjectMapper()

    /**
     * 生成工具目录文本
     *
     * @param query 关键词：空白 = 全部工具；否则按名字或描述模糊匹配（忽略大小写）
     * @return Markdown 目录（每条含 名字/功能/何时使用/参数）；无匹配时给提示
     */
    fun catalog(query: String?): String {
        val q = query?.trim().orEmpty()
        val all = toolsProvider()
        val matched = if (q.isEmpty()) {
            all
        } else {
            all.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true)
            }
        }
        if (matched.isEmpty()) {
            return "（没有匹配「$q」的工具；用 list_tools 不带 query 可查看全部 ${all.size} 个工具）"
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
    fun count(query: String?): Int {
        val q = query?.trim().orEmpty()
        val all = toolsProvider()
        return if (q.isEmpty()) all.size
        else all.count {
            it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true)
        }
    }

    /**
     * 执行一次工具调用
     *
     * @param name 工具名（忽略大小写；目录中的名字）
     * @param input LLM 给的参数对象（Jackson 转 JSON 后走 Tool 的 JSON 边界）
     * @return 工具结果 JSON；工具不存在 / 执行异常时返回 `{"success":false,"error":...}`
     */
    fun invoke(name: String, input: Map<String, Any?>): String {
        val tool = toolsProvider().find { it.name.equals(name, ignoreCase = true) }
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

    private fun errorJson(message: String): String =
        """{"success":false,"error":${mapper.writeValueAsString(message)}}"""
}

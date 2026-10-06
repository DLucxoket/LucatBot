package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 工具调用的**结构化结果**（[ToolCatalog.invokeDetailed] 的返回值）
 *
 * 此前编排层（ConversationController 的 CALL_TOOL 分支）只能拿 `invoke` 的
 * 纯文本再用 `contains("\"error\"")` 嗅探是否失败——成功正文里恰好出现
 * `error` 字样就会误报。本类型把「输出文本」与「是否失败」分开携带，
 * 编排层读 [isError] 即可，不再对 JSON 猜字符串。
 *
 * @param output 注入 prompt 的文本——可能是错误 JSON，也可能被
 *   `maxResultChars` 截断（**截断后不再是合法 JSON，只当文本用**）
 * @param isError 本次调用是否失败（判定规则见 [ToolCatalog.invokeDetailed]）
 */
data class ToolCallResult(
    val output: String,
    val isError: Boolean
)

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
 * @param maxResultChars 单次执行结果注入 prompt 的最大长度（防爆 context）；
 *   4_000 → **3_000**（P1 token 精简，与 `ToolNotes` 总预算 16k→10k 配套——
 *   让「目录 ~3k + 1~2 条结果」稳落预算内；关键信息 3k 够放，更长的（长列表/
 *   网页正文）本就该收窄范围再查）
 * @param orchestrationOnly 目录与调用均排除的工具名（忽略大小写）
 */
class ToolCatalog(
    private val toolsProvider: () -> List<Tool<*, *>> = { ToolDiscovery.getTools() },
    private val maxResultChars: Int = 3_000,
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
     * 执行一次工具调用（**结构化结果**——判失败读 [ToolCallResult.isError]）
     *
     * **编排层接线说明**：ConversationController 的 CALL_TOOL 分支应改用本方法
     * 替代对 `output` 的字符串嗅探（`result.contains("\"error\"")`——成功正文含
     * `error` 字样会误报），接线由集成方完成；本类不改动编排层。
     *
     * `isError` 判定（在**截断之前**用 [com.fasterxml.jackson.databind.ObjectMapper.readTree]
     * 解析完整结果，故截断不影响判定）：
     * 1. 本类自己的错误路径（编排专属 / 工具不存在 / 执行异常转错误 JSON）→ `true`；
     * 2. 工具结果 JSON 里 `success == false` → `true`；
     * 3. 工具结果 JSON 顶层 `error` 为非空字符串 → `true`
     *    （`UserProfileResult` 这类**没有 success 字段**的失败结果靠它兜住，
     *    与 `ToolErrorContractTest` 的错误口径一致）；
     * 4. 结果不是 JSON 对象 / 解析失败 / 两者皆无 → `false`
     *    （工具可能返回非 JSON 文本，解析失败**不视为错误**）。
     *
     * @param name 工具名（忽略大小写；目录中的名字）
     * @param input LLM 给的参数对象（Jackson 转 JSON 后走 Tool 的 JSON 边界）
     */
    fun invokeDetailed(name: String, input: Map<String, Any?>): ToolCallResult {
        if (orchestrationOnly.any { it.equals(name, ignoreCase = true) }) {
            return ToolCallResult(
                output = errorJson("工具「$name」由编排层专属管理，不可经 tool 调用"),
                isError = true
            )
        }
        val tool = visibleTools().find { it.name.equals(name, ignoreCase = true) }
            ?: return ToolCallResult(
                output = errorJson("工具「$name」不存在；先用 list_tools 查看可用工具"),
                isError = true
            )
        return try {
            val inputJson = mapper.writeValueAsString(input)
            val resultJson = tool.process(inputJson)
            // 判失败必须用**截断前**的完整 JSON（截断会破坏结构导致解析失败→漏判）
            val isError = looksLikeFailure(resultJson)
            val output = if (resultJson.length > maxResultChars) {
                resultJson.take(maxResultChars) + "…（结果超长已截断）"
            } else {
                resultJson
            }
            ToolCallResult(output = output, isError = isError)
        } catch (e: Exception) {
            ToolCallResult(
                output = errorJson("${e.javaClass.simpleName}: ${e.message}"),
                isError = true
            )
        }
    }

    /**
     * 执行一次工具调用（只要注入 prompt 的文本）
     *
     * 行为与历史版本逐字一致（大小写匹配、编排专属拒绝、超长截断、异常转错误 JSON）；
     * 委托 [invokeDetailed] 后仅取其 [ToolCallResult.output]。
     *
     * 需要判断失败与否的调用方（编排层）请改用 [invokeDetailed]。
     *
     * @param name 工具名（忽略大小写；目录中的名字）
     * @param input LLM 给的参数对象（Jackson 转 JSON 后走 Tool 的 JSON 边界）
     * @return 工具结果 JSON；工具不存在 / 编排专属 / 执行异常时返回错误 JSON
     */
    fun invoke(name: String, input: Map<String, Any?>): String =
        invokeDetailed(name, input).output

    /**
     * 结果 JSON 的失败判定（只认结构不认子串；**调用方保证传截断前的完整 JSON**）
     *
     * 解析失败或非对象一律返回 `false`——工具可能返回非 JSON 文本，
     * 那不是「错误结果」，编排层照常注入即可。
     */
    private fun looksLikeFailure(resultJson: String): Boolean {
        val node = try {
            mapper.readTree(resultJson)
        } catch (e: Exception) {
            return false // 非 JSON / 解析失败 → 不视为错误
        }
        if (node == null || !node.isObject) return false
        val success = node.get("success")
        if (success != null && !success.isNull && success.isBoolean && !success.asBoolean()) {
            return true
        }
        val error = node.get("error")
        return error != null && !error.isNull && error.isTextual && error.asText().isNotBlank()
    }

    /** 错误结果统一经 mapper 序列化（结构转义安全，不在业务里手拼 JSON） */
    private fun errorJson(message: String): String =
        mapper.writeValueAsString(mapOf("success" to false, "error" to message))
}

package org.dlucxoket.lucatbot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.dlucxoket.lucatbot.bot.tool.ToolDiscovery
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tool 错误契约守卫：**带 error 的结果绝不允许 success=true**
 *
 * 这是 OOP 审查的回归护栏——此前 13 个 Tool 的 catch 返回
 * `XResult(error = ...)` 而 success 默认 true，LLM 会收到自相矛盾
 * 信号（踢人失败却显示成功）。任何新增 Tool 违反此契约都会被本测试
 * 捕获（工具抛异常不算违规：ToolCatalog.invoke 会兜底转错误 JSON）。
 */
class ToolErrorContractTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `no tool returns error with success true`() {
        val violations = mutableListOf<String>()

        for (tool in ToolDiscovery.getTools()) {
            val resultJson = try {
                tool.process("{}")
            } catch (_: Exception) {
                continue  // 抛异常由 ToolCatalog.invoke 兜底，不属契约违规
            }
            val map = try {
                mapper.readValue(resultJson, Map::class.java)
            } catch (_: Exception) {
                continue  // 非对象结果无法判定，跳过
            }
            val success = map["success"]
            val error = map["error"]
            if (success == true && error != null) {
                violations += "${tool.name}: error=$error 但 success=true"
            }
        }

        assertTrue(
            violations.isEmpty(),
            "错误契约违规（error 非空时必须 success=false）：\n" + violations.joinToString("\n")
        )
    }

    @Test
    fun `discovery finds a reasonable number of tools`() {
        // 27 个真实 Tool + 测试类路径里的 Fake/样本工具
        assertTrue(ToolDiscovery.getTools().size >= 27, "发现数=${ToolDiscovery.getTools().size}")
    }
}

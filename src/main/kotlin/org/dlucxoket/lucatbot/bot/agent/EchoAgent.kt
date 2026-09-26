package org.dlucxoket.lucatbot.bot.agent

/**
 * 回声 Agent（测试用）
 *
 * 模拟真人行为验证：最简单的链路连通性测试，不代表真实用户行为。
 * 真实行为由 [ReplyAgent] 和未来的 `SessionPickerAgent` 等承担。
 *
 * 输入：`{"text": "要回显的内容"}`
 * 输出：`{"echo": "Echo: xxx"}`
 */
object EchoAgent : Agent() {
    override val description: String = """
        回声测试Agent，将输入的内容原样返回。
        用于验证系统链路是否通畅，不具备实际业务能力。
    """.trimIndent()

    override fun process(input: String): String {
        // 解析 JSON 输入
        val text = try {
            val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(input)
            node.get("text")?.asText() ?: input
        } catch (e: Exception) {
            input
        }

        // 返回 JSON 结果
        return """{"echo": "Echo: $text"}"""
    }
}

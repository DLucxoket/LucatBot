package org.dlucxoket.lucatbot.bot.agent

/**
 * 回声 Agent
 *
 * 将输入原样返回，用于验证 Agent 链路连通性。
 * 最简单的 Agent 实现，不依赖 LLM。
 */
object EchoAgent : Agent() {
    override val description: String = """
        回声测试Agent，将输入的内容原样返回。
        用于验证系统链路是否通畅，不具备实际业务能力。
    """.trimIndent()

    override fun process(input: String): String {
        return "Echo: $input"
    }
}

package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.EchoRequest
import org.dlucxoket.lucatbot.bot.agent.data.EchoResult

/**
 * 回声 Agent（测试用）
 *
 * 验证 JSON ↔ OOP 边界转换和链路连通性。
 * 真实行为由 [ReplyAgent] 等承担。
 *
 * JSON 边界：`{"text": "..."}` ↔ `EchoRequest`
 * OOP 内部：`EchoRequest` → `EchoResult`
 */
object EchoAgent : Agent<EchoRequest, EchoResult>() {
    override val description: String = """
        回声测试Agent，将输入的内容原样返回。
        用于验证系统链路是否通畅，不具备实际业务能力。
    """.trimIndent()

    override val inputClass: Class<EchoRequest> = EchoRequest::class.java

    /**
     * OOP 处理：接收 [EchoRequest]，返回 [EchoResult]
     */
    override fun handle(input: EchoRequest): EchoResult {
        return EchoResult(echo = "Echo: ${input.text}")
    }
}

package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.llm.OpenAIAPI
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 端点异常的**分级兜底**判定（[OpenAIAPI.isConfigFailure]）
 *
 * 实盘故障：端点偶发返回 SSE（响应体 `data: …`），langchain4j 抛
 * `JsonParseException: Unrecognized token 'data'`，异常一路抛到主循环。
 * 判定规则要**保守**：坏响应 → 降级成空串让调用方兜底；配置错误 → 必须上抛
 * （把配错端点伪装成「模型没说话」会让排查方向跑偏）。
 */
class LlmFailureDegradeTest {

    @Test
    fun `config failures must be rethrown not degraded`() {
        assertTrue(OpenAIAPI.isConfigFailure("HTTP 401 Unauthorized"), "鉴权失败")
        assertTrue(OpenAIAPI.isConfigFailure("403 Forbidden"), "无权限")
        assertTrue(OpenAIAPI.isConfigFailure("404 Not Found"), "端点/模型路径不存在")
        assertTrue(OpenAIAPI.isConfigFailure("The model `xxx-vision` does not exist"), "模型名配错")
        assertTrue(OpenAIAPI.isConfigFailure("Invalid API key"), "密钥配错")
    }

    @Test
    fun `bad responses and transient errors degrade instead of crashing the loop`() {
        // 实盘原文：响应体以 data: 开头（SSE），JSON 解析在第 1 行第 5 列炸
        assertFalse(
            OpenAIAPI.isConfigFailure(
                "com.fasterxml.jackson.core.JsonParseException: Unrecognized token 'data': " +
                    "was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false') " +
                    "at [Source: REDACTED; line: 1, column: 5]"
            ),
            "坏响应（SSE 当 JSON）不是配置错误 → 降级成空串"
        )
        assertFalse(OpenAIAPI.isConfigFailure("timeout"), "超时 → 降级")
        assertFalse(OpenAIAPI.isConfigFailure("HTTP 429 Too Many Requests"), "限流 → 降级（下轮再试）")
        assertFalse(OpenAIAPI.isConfigFailure("connection reset"), "网络抖动 → 降级")
        assertFalse(OpenAIAPI.isConfigFailure(null), "识别不了 → 保守当可降级（宁可多一条 WARN）")
    }
}

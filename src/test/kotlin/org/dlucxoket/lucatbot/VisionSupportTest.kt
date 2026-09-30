package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.llm.VisionSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 模型视觉能力记忆：判定纯函数的保守性 + 进程级状态机
 *
 * 设计要点（对应需求）：不持久化——一次运行里失败一次记住就够，
 * 重启后再探测一次；状态是 object 内存变量，用例间必须 reset。
 */
class VisionSupportTest {

    @AfterEach
    fun tearDown() {
        VisionSupport.reset()
    }

    @Test
    fun `detects typical unsupported vision rejections`() {
        // 实盘（硅基流动 Qwen3-8B，2026-09-30 日志原文）：业务码 20041、无 HTTP 码
        assertTrue(VisionSupport.looksLikeVisionUnsupported(
            """{"code":20041,"message":"The model is not a VLM (Vision Language Model). Please use text-only prompts.","data":null}"""),
            "硅基流动实盘文案必须命中——漏了它会每轮白带图")
        assertTrue(VisionSupport.looksLikeVisionUnsupported(
            "Error code: 400 - {'error': {'message': 'Invalid content type. image_url is not supported'}}"),
            "OpenAI 系标准文案")
        assertTrue(VisionSupport.looksLikeVisionUnsupported(
            "This model does not support image input"), "模型能力声明型")
        assertTrue(VisionSupport.looksLikeVisionUnsupported(
            "400 bad request: multimodal content is not supported"), "400 + multimodal")
        assertTrue(VisionSupport.looksLikeVisionUnsupported("400 图片内容不支持"), "中文文案")
    }

    @Test
    fun `transient failures are not misclassified`() {
        assertFalse(VisionSupport.looksLikeVisionUnsupported("connection timed out"), "网络超时")
        assertFalse(VisionSupport.looksLikeVisionUnsupported("429 Too Many Requests"), "限流")
        assertFalse(VisionSupport.looksLikeVisionUnsupported("401 Unauthorized"), "鉴权")
        assertFalse(VisionSupport.looksLikeVisionUnsupported("404 image.png not found"), "404 不是能力拒绝")
        assertFalse(VisionSupport.looksLikeVisionUnsupported("Read timed out referencing image.png"),
            "提到图片但无拒绝语义 → 不误记")
        assertFalse(VisionSupport.looksLikeVisionUnsupported(null), "空消息")
        assertFalse(VisionSupport.looksLikeVisionUnsupported(""), "空串")
    }

    @Test
    fun `state machine marks only explicit verdicts`() {
        assertEquals(VisionSupport.State.UNKNOWN, VisionSupport.state, "初始未探测")
        assertFalse(VisionSupport.unsupported())

        VisionSupport.markSupported()
        assertEquals(VisionSupport.State.SUPPORTED, VisionSupport.state, "带图成功 → 支持")
        assertFalse(VisionSupport.unsupported())

        VisionSupport.markUnsupported()  // 后续若再命中不支持错误，覆盖支持态
        assertTrue(VisionSupport.unsupported(), "明确判定 → 不支持")

        VisionSupport.reset()
        assertEquals(VisionSupport.State.UNKNOWN, VisionSupport.state, "reset 回未探测")
    }
}

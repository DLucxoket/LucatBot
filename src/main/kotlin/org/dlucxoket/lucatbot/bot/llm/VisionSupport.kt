package org.dlucxoket.lucatbot.bot.llm

/**
 * 模型视觉能力记忆（进程级，探测一次、后续复用）
 *
 * 为什么需要：端点不支持识图时，带图请求会被 4xx 拒绝、每次都要
 * 「带图失败 → 纯文本重试」走一遍——双倍请求 + 重试延迟，且永远学不会。
 * 本对象记住探测结论：**一旦确认不支持，后续请求不再携带图片内容**。
 *
 * - [UNKNOWN]：尚未探测（带图正常尝试）
 * - [SUPPORTED]：带图请求成功过
 * - [UNSUPPORTED]：命中「不支持识图」类错误——[OpenAIAPI] 三参直接走纯文本，
 *   编排层（ConversationController）跳过图片取材（不下载、不救援），
 *   refs 如实进缺失清单交给 prompt 现有约束链
 *
 * 状态由 [OpenAIAPI] 读写；判定逻辑是纯函数（可单测）。
 * 判定刻意保守：**必须同时**提到图片/多模态、且带拒绝语义——网络超时、
 * 429、鉴权错误不匹配，不会被误记成「不支持」。
 */
object VisionSupport {

    enum class State { UNKNOWN, SUPPORTED, UNSUPPORTED }

    @Volatile
    var state: State = State.UNKNOWN
        private set

    /** 已确认不支持识图（编排层据此跳过取材） */
    fun unsupported(): Boolean = state == State.UNSUPPORTED

    /** 带图请求成功 → 记住支持 */
    fun markSupported() {
        state = State.SUPPORTED
    }

    /** 命中不支持类错误 → 记住不支持（覆盖此前状态） */
    fun markUnsupported() {
        state = State.UNSUPPORTED
    }

    /** 测试复位（进程级状态，用例间必须清干净） */
    fun reset() {
        state = State.UNKNOWN
    }

    /**
     * 判断带图请求的失败是否属于「模型不支持识图」（纯函数）
     *
     * 两个条件同时满足才算：
     * 1. 消息提到图片/多模态（image / multimodal / vision / vlm / 图片…）
     * 2. 带拒绝语义（not support / not a vlm / text-only / invalid /
     *    400 / 422 / bad request / 业务码 20041 / 不支持…）
     *
     * 覆盖的真实文案：
     * - 硅基流动：`{"code":20041,"message":"The model is not a VLM
     *   (Vision Language Model). Please use text-only prompts."}`（实盘）
     * - OpenAI 系：`Invalid content type. image_url is not supported`
     * - `This model does not support image input`
     * - `400 - multimodal content is not supported`
     */
    fun looksLikeVisionUnsupported(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        val mentionsImage = listOf("image", "multimodal", "vision", "vlm", "图片").any { m.contains(it) }
        if (!mentionsImage) return false
        return listOf(
            "not support", "unsupported", "not a vlm", "text-only", "text only",
            "invalid", "bad request", "400", "422", "20041", "not allowed",
            "不支持", "拒绝"
        ).any { m.contains(it) }
    }
}

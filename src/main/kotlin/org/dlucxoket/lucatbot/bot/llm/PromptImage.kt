package org.dlucxoket.lucatbot.bot.llm

/**
 * 附在 LLM 请求上的图片（**仅存在于 LLM API 边界附近**）
 *
 * 链路：Shiro 消息段的 url（入档存 [org.dlucxoket.lucatbot.bot.chathistory.ChatMessageRecord.imageUrls]）
 * → [ImageResolver] 下载加工成 data URI → Agent 的 Request 携带 → [OpenAIAPI]
 * 在边界组装成 OpenAI content 数组（`image_url` 段）。
 *
 * - [ref] 关联标识：聊天记录占位符里的 file/url 标签（如 `A1D1….jpg`），
 *   供文本侧与附件对应（边界会渲染成 `[图片 A1D1….jpg]` 标签文本）
 * - [dataUri] `data:image/png;base64,…`——进边界前的唯一携带形式
 *
 * 注意：不要把本对象写进持久化或日志（dataUri 是大块 base64）。
 */
data class PromptImage(
    val ref: String,
    val dataUri: String
)

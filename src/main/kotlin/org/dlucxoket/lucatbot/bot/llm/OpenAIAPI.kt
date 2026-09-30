package org.dlucxoket.lucatbot.bot.llm

import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.openai.OpenAiChatModel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 基于 OpenAI 协议的 LLM 实现
 *
 * 支持任何兼容 OpenAI API 格式的服务，包括：
 * - OpenAI GPT 系列
 * - Azure OpenAI
 * - 本地部署的 Qwen、Llama 等（通过 Ollama、vLLM 等）
 * - 其他第三方兼容服务
 *
 * 双路径语义（本类有两个实例来源，务必分清）：
 *
 * 1. **Spring Bean**（本类标注 `@Component`）：`@Value` 注入 `application.yaml`
 *    中的 `llm.mimo.*` 配置。该实例由 `LlmConfig` 注册到 [LLM.install]，
 *    Spring 环境下 [LLM.DEFAULT] 的实际调用目标。
 * 2. **手 new 兜底实例**（[DEFAULT] companion，非 Bean）：供非 Spring 环境
 *    （单元测试直接 new Agent）使用。不经 Spring 处理，`@Value` 不生效，
 *    配置完全来自下方字段初始化器的环境变量/系统属性 fallback。
 *
 * 注意：每次 `ask()` 调用都会重新构建 `OpenAiChatModel` 实例。
 * 这在低频场景下没问题，但在高频调用时可能有性能开销。
 */
@Component
class OpenAIAPI: LLM {
    private val log = LoggerFactory.getLogger(OpenAIAPI::class.java)

    companion object {
        /**
         * 默认实例（手动创建，刻意非 Spring Bean）——仅作非 Spring 环境的兜底。
         *
         * [LLM.DEFAULT] 的委托在 `springInstance` 为 null（即未经过 Spring 启动）
         * 时才转发到此实例。Spring 环境下本字段通常不会被创建。
         */
        val DEFAULT: OpenAIAPI by lazy { OpenAIAPI() }
    }

    /*
     * 配置字段说明：
     * - Spring 路径：@Value 在构造后注入。application.yaml 的 llm.mimo.* 三项齐全时
     *   覆盖字段初始化器的值（正常生效路径）。若某项从 yaml 缺失，@Value 默认空串
     *   也会覆盖初始化器——因此 yaml 三项必须保持齐全。
     * - 非 Spring 路径：@Value 不被处理，字段初始化器的 env/系统属性 fallback 生效。
     */

    /** API 端点地址（Spring：yaml llm.mimo.base-url；兜底实例：LLM_BASE_URL 环境变量/系统属性） */
    @Value($$"${llm.mimo.base-url:}")
    private var baseUrl: String = System.getenv("LLM_BASE_URL")
        ?: System.getProperty("llm.mimo.base-url")
        ?: "https://token-plan-cn.xiaomimimo.com/v1"

    /** API 密钥（Spring：yaml llm.mimo.api-key；兜底实例：LLM_API_KEY 环境变量/系统属性） */
    @Value($$"${llm.mimo.api-key:}")
    private var apiKey: String = System.getenv("LLM_API_KEY")
        ?: System.getProperty("llm.mimo.api-key")
        ?: "ollama"

    /** 模型名称（Spring：yaml llm.mimo.model-name；兜底实例：LLM_MODEL_NAME 环境变量/系统属性） */
    @Value($$"${llm.mimo.model-name:}")
    private var modelName: String = System.getenv("LLM_MODEL_NAME")
        ?: System.getProperty("llm.mimo.model-name")
        ?: "mimo-v2.5"


    /**
     * 构建 LangChain4j 的 OpenAiChatModel 实例
     *
     * 每次调用都创建新实例，确保配置（baseUrl/apiKey/modelName）的最新值生效。
     *
     * @return 配置好的 OpenAiChatModel，可直接发起聊天请求
     */
    private fun createModel(): OpenAiChatModel {
        return OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .modelName(modelName)
            .timeout(java.time.Duration.ofSeconds(120))  // 2 分钟超时
            .maxRetries(2)
            .build()
    }

    /**
     * 执行一次 LLM 对话调用
     *
     * 流程：
     * 1. 构建消息列表（有系统提示时：SystemMessage + UserMessage；否则仅 UserMessage）
     * 2. 封装为 LangChain4j 的 ChatRequest
     * 3. 发送请求并提取 AI 回复文本
     *
     * @param systemPrompt 系统提示词（可为空）
     * @param question     用户问题
     * @return LLM 生成的回复文本
     */
    override fun ask(systemPrompt: String, question: String): String {
        val model = createModel()

        /**
         * 构建消息列表
         * - systemPrompt 非空 → 先发系统消息设定角色，再发用户消息
         * - systemPrompt 为空 → 仅发用户消息
         */
        val messages = if (systemPrompt.isNotBlank()) {
            listOf(
                SystemMessage.systemMessage(systemPrompt),
                UserMessage.userMessage(question)
            )
        } else {
            listOf(UserMessage.userMessage(question))
        }

        /** 将消息列表封装为 LangChain4j 聊天请求对象 */
        val chatRequest = ChatRequest.builder()
            .messages(messages)
            .build()

        /** 发送请求并获取 AI 消息，提取纯文本返回 */
        val response = model.chat(chatRequest)
        return response.aiMessage().text()
    }

    /**
     * 多模态调用——**图片通道的 LLM 边界**（JSON 只在这里拼）
     *
     * content 数组按顺序组装：正文（text）→ 每张图先一段标签文本
     * `[图片 <ref>]`（与聊天记录占位符同名，供模型对应是哪条消息的图）→ 图片
     * （`image_url`，data URI）。空图片列表等价于两参 [ask]。
     *
     * **能力记忆**（[VisionSupport]，进程级不持久化）：确认不支持识图后
     * 直接走纯文本——不再每轮带图试探（一次运行失败一次就够）；带图成功则
     * 记住支持。
     *
     * **降级保护**：带图请求失败一律退回纯文本重试一次——图片通道绝不让
     * 整条会话链路失败；其中命中「不支持识图」类错误才记为 UNSUPPORTED，
     * 网络超时/429/鉴权错误不误记。
     */
    override fun ask(systemPrompt: String, question: String, images: List<PromptImage>): String {
        if (images.isEmpty()) return ask(systemPrompt, question)
        // 已确认模型不支持识图：不给图片内容，直接纯文本（省一次必败请求与重试延迟）
        if (VisionSupport.unsupported()) return ask(systemPrompt, question)
        return try {
            val contents = userContents(question, images)
            val messages = if (systemPrompt.isNotBlank()) {
                listOf(SystemMessage.systemMessage(systemPrompt), UserMessage(contents))
            } else {
                listOf(UserMessage(contents))
            }
            val chatRequest = ChatRequest.builder().messages(messages).build()
            val result = createModel().chat(chatRequest).aiMessage().text()
            VisionSupport.markSupported()
            result
        } catch (e: Exception) {
            if (VisionSupport.looksLikeVisionUnsupported(e.message)) {
                VisionSupport.markUnsupported()
                log.warn("模型不支持识图（{}）——本次运行已记住，后续请求不再携带图片", e.message)
            } else {
                log.warn("带图片请求失败（{}），降级为纯文本重试", e.message)
            }
            ask(systemPrompt, question)
        }
    }

    /**
     * content 数组组装（抽出来便于测试）：正文 → 每张图先一段标签文本
     * `[图片 <ref>]`（与聊天记录占位符同名，供模型对应是哪条消息的图）→ 图片段。
     */
    internal fun userContents(question: String, images: List<PromptImage>): List<Content> = buildList {
        add(TextContent(question))
        images.forEach { img ->
            add(TextContent("\n[图片 ${img.ref}]"))
            add(ImageContent(img.dataUri))
        }
    }
}

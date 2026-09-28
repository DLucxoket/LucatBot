package org.dlucxoket.lucatbot.bot.llm

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.openai.OpenAiChatModel
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
}

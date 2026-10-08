package org.dlucxoket.lucatbot.bot.llm

import dev.langchain4j.data.message.Content
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
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
class OpenAIAPI : LLM {

    private val log = LoggerFactory.getLogger(OpenAIAPI::class.java)

    companion object {
        /**
         * 默认实例（手动创建，刻意非 Spring Bean）——仅作非 Spring 环境的兜底。
         *
         * [LLM.DEFAULT] 的委托在 `springInstance` 为 null（即未经过 Spring 启动）
         * 时才转发到此实例。Spring 环境下本字段通常不会被创建。
         */
        val DEFAULT: OpenAIAPI by lazy { OpenAIAPI() }

        /** 配置类错误的识别标记（命中即**上抛**，不降级——见 [degradeOrThrow]） */
        private val CONFIG_ERROR_MARKERS = listOf(
            "401", "403", "404",
            "unauthorized", "forbidden",
            "api key", "apikey", "invalid model", "model not found", "does not exist"
        )

        /**
         * 是否属于**配置类**错误（鉴权/端点/模型名）——该上抛、不该降级成空串
         *
         * 判定刻意保守：**识别不了就当可降级**（宁可多一条 WARN，也别把一次
         * 坏响应判成配错、让整条链路崩回原样）。公开为纯函数便于单测。
         */
        fun isConfigFailure(message: String?): Boolean {
            val m = message ?: return false
            return CONFIG_ERROR_MARKERS.any { m.contains(it, ignoreCase = true) }
        }
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
     * 单次**输出**上限（token），yaml `llm.mimo.max-tokens` 可调（缺省 4096）
     *
     * 为什么显式给：deepseek-v4 这类推理型模型会先吐 reasoning——输出预算太小时
     * reasoning 耗完就交白卷（`content` 为空 → langchain4j 抛
     * `text cannot be null` → 实盘把这轮判成端点故障、对话被无谓打断）。
     * 给个明确上限让它有预算把答案写完；我们要的是短 JSON，**上限只是天花板不是成本**
     *（Int 带 @Value 默认值，yaml 缺项也回退 4096，不会像 String 那样被空串覆盖）。
     */
    @Value($$"${llm.mimo.max-tokens:4096}")
    private var maxTokens: Int = 4096


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
            .maxTokens(maxTokens)  // 给推理型模型留足输出预算（见字段注释：空响应的最可能成因）
            .timeout(java.time.Duration.ofSeconds(120))  // 2 分钟超时
            .maxRetries(2)
            .build()
    }

    /**
     * 发一次请求，**非配置类失败补一次重试**（300ms 后）
     *
     * langchain4j 自带的 `maxRetries=2` 只覆盖传输层重试，这两类它不管：
     * - **`text cannot be null`**——HTTP 200 但 `content` 为空（实盘 deepseek-v4：
     *   推理型模型把预算耗在 reasoning 上交白卷），在响应转换阶段抛异常；
     * - **转换前就炸的畸形响应**（SSE `data: …` 当 JSON 那类）。
     *
     * 空响应重试一次多半能救回来——实盘一次空响应曾把整段对话打断（read_more
     * 刚翻完楼就 return 离开）。救不回再走 [degradeOrThrow] 的分级降级。
     */
    private fun chatWithOneRetry(model: OpenAiChatModel, request: ChatRequest): ChatResponse {
        try {
            return model.chat(request)
        } catch (e: Exception) {
            if (isConfigFailure(e.message)) throw e // 配置错误重试也没用，别浪费一次
            log.warn("LLM 调用失败（{}），300ms 后补一次重试", e.message)
        }
        runCatching { Thread.sleep(300) } // 中断（停机）就直接往下走，让重试自然失败并降级
        return model.chat(request)
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
        val response = try {
            chatWithOneRetry(model, chatRequest)
        } catch (e: Exception) {
            // 端点偶发坏响应（实盘：SSE `data: …` 被当 JSON 解析）→ 分级兜底
            return degradeOrThrow(e, TokenUsageChannel.TEXT)
        }
        recordUsage(response, TokenUsageChannel.TEXT)
        val text = response.aiMessage().text()
        if (text.isNullOrBlank()) {
            log.warn("LLM 返回空文本（端点异常/输出被吞/finish_reason 截断），调用方会按解析失败兜底")
        }
        return text
    }

    /**
     * 端点异常的**分级兜底**
     *
     * 实盘故障：端点偶发返回 SSE（响应体以 `data:` 开头），langchain4j 解析
     * `JsonParseException: Unrecognized token 'data'`，`maxRetries=2` 用尽后异常
     * 一路抛到主循环（会话轮还会连带整段退出）。这类**坏响应/网络问题**不值得
     * 打崩链路——降级成**空串**，调用方走既有的「LLM 返回空文本 → 解析失败兜底」
     * 路径（决策轮兜底 return、选会话兜底 idle、记忆整理兜底空结果）。
     *
     * 但**配置类错误**（鉴权/端点/模型名）必须**上抛**——伪装成「模型没说话」
     * 会让排查方向跑偏；配错端点时每轮一条 WARN 也远比一次 ERROR 难发现。
     *
     * 降级时**同样记一笔账**（用量未知 → 0 token + 计入「未返回用量」）：
     * 失败调用是可用性信号，面板上要看得见。
     */
    private fun degradeOrThrow(e: Exception, channel: TokenUsageChannel): String {
        if (isConfigFailure(e.message)) throw e
        log.warn(
            "LLM 调用失败（{}），降级为空串让调用方按解析失败兜底（模型 {}，通道 {}）",
            e.message, modelName, channel.label
        )
        TokenUsageScope.stashUsage(TokenUsagePending(channel, modelName, null, null))
        return ""
    }

    /**
     * 记录一次调用的 token 用量（**量化优先**：先拿真实分布，再谈精简哪里）
     *
     * 两处去向：
     * - **日志**：打全三个维度 `[通道][agent][会话]`——端点不回 usage 时如实标注
     *   「未返回」，不猜数；模型名一起打，换端点/换模型后的成本差异一眼可比
     * - **暂存**：[TokenUsageScope.stashUsage] 把原始用量挂到当前线程，由产出这次
     *   调用的 [org.dlucxoket.lucatbot.bot.agent.Agent] 在 handle 结束时带**决策标签**
     *   （send / idle / … 与进场首轮）统一记进 [TokenUsageAggregator]——
     *   传输层看不到决策结果，补标就得改写 append-only 明细，故记账时机上移
     *
     * agent/会话两个维度取自 [TokenUsageScope]（Agent 入口与编排层在调用前挂上），
     * 这里只负责取——[LLM] 接口不因此加参数（ISP）。
     */
    private fun recordUsage(response: ChatResponse, channel: TokenUsageChannel) {
        val usage = response.tokenUsage()
        val agent = TokenUsageScope.agent()
        val conversation = TokenUsageScope.conversation()
        if (usage == null) {
            log.info(
                "LLM用量[{}][{}][{}]：端点未返回 tokenUsage（模型 {}）",
                channel.label, agent, conversation, modelName
            )
        } else {
            // 显式调 getter：TokenUsage 的同名字段是 private，
            // Kotlin 属性语法会解析到字段上（编译期「不可访问」）
            log.info(
                "LLM用量[{}][{}][{}]：输入 {} / 输出 {} / 合计 {} token（模型 {}）",
                channel.label, agent, conversation,
                usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount(), modelName
            )
        }
        TokenUsageScope.stashUsage(
            TokenUsagePending(
                channel = channel,
                model = modelName,
                input = usage?.inputTokenCount()?.toLong(),
                output = usage?.outputTokenCount()?.toLong()
            )
        )
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
            val response = chatWithOneRetry(createModel(), chatRequest) // 同文本路：非配置失败补一次
            recordUsage(response, TokenUsageChannel.IMAGE)
            val result = response.aiMessage().text()
            if (result.isNullOrBlank()) {
                log.warn("LLM 返回空文本（带图请求），调用方会按解析失败兜底")
            }
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

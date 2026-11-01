package org.dlucxoket.lucatbot.bot.llm


/**
 * 大语言模型（LLM）统一抽象接口
 *
 * 设计意图：
 * - 对上层（Agent 等）屏蔽底层模型实现的差异，调用方只需关心 `ask()` 方法
 * - 通过 [DEFAULT] 提供全局默认实例，避免每次手动传递
 *
 * 实现约定：
 * - `ask(systemPrompt, question)`：带系统提示的完整调用，systemPrompt 为空时退化为纯用户消息
 * - `ask(question)`：便捷重载，等价于 `ask("", question)`
 * - `askWithContext(systemPrompt, history, question)`：API 原生多轮上下文（messages 数组）
 *
 * 当前唯一实现：[OpenAIAPI]（兼容任意 OpenAI 协议端点）
 */
interface LLM {
    companion object {
        /**
         * Spring 容器管理的 LLM 实例。
         *
         * 由 [org.dlucxoket.lucatbot.bot.llm.LlmConfig] 在启动时通过 [install] 注册
         * （即 `@Value` 配置注入完毕的 `OpenAIAPI` Bean）。
         */
        @Volatile
        private var springInstance: LLM? = null

        /**
         * 注册 Spring 容器管理的 LLM Bean，作为 [DEFAULT] 的实际实现。
         *
         * 仅应由 Spring 环境调用一次；非 Spring 环境（单元测试）不注册，
         * [DEFAULT] 会落到 [OpenAIAPI.DEFAULT] 兜底。
         *
         * @param llm 要注册的实例；传 `null` 清除注册（单元测试收尾用，
         *   避免测试替身残留在同 JVM 中污染后续用例）
         */
        fun install(llm: LLM?) {
            springInstance = llm
        }

        /**
         * 全局默认 LLM 实例——一个恒定的轻量委托对象。
         *
         * 设计要点（两条路径）：
         * - Spring 环境：`springInstance` 已由 LlmConfig 注册，`ask()` 转发到容器
         *   管理的 `OpenAIAPI` Bean（平台配置读 application.yaml 的 `llm.active` + `llm.platforms.<active>.*`）。
         * - 非 Spring 环境（单元测试直接 new Agent）：`springInstance` 为 null，
         *   `ask()` 转发到 [OpenAIAPI.DEFAULT]（手 new 实例，读环境变量 fallback）。
         *
         * 为什么是委托对象而非「每次 getter 返回当前实例」：Agent（如 LoopConfig 中的
         * ConversationAgent、MessageListController 的 SessionPickerAgent）在 Spring 启动期构造时
         * 会把 [DEFAULT] 作为默认构造参数捕获——若直接返回原始实例，捕获发生在注册之前
         * 就会永久拿到兜底实例。委托对象把解析推迟到 `ask()` 调用时，彻底消除注册顺序依赖。
         */
        val DEFAULT: LLM = object : LLM {
            override fun ask(systemPrompt: String, question: String): String =
                (springInstance ?: OpenAIAPI.DEFAULT).ask(systemPrompt, question)

            // 三参（图片通道）必须与两参同步转发——委托对象只 override 两参时，
            // 图片会走接口默认实现被静默丢弃，生产 ConversationAgent（默认注入
            // DEFAULT）的全部图片取材白做（第四轮 OOP 审查 P1-1）
            override fun ask(systemPrompt: String, question: String, images: List<PromptImage>): String =
                (springInstance ?: OpenAIAPI.DEFAULT).ask(systemPrompt, question, images)

            // 多轮上下文同理：委托对象必须显式转发，否则走接口默认的「折叠成一条
            // user 文本」降级路径——多轮历史会被压平，API 原生 messages 上下文白带
            override fun askWithContext(systemPrompt: String, history: List<LlmTurn>, question: String): String =
                (springInstance ?: OpenAIAPI.DEFAULT).askWithContext(systemPrompt, history, question)
        }
    }

    /**
     * 向 LLM 发起提问
     *
     * @param systemPrompt 系统提示词，用于设定 AI 的角色/行为约束；为空字符串时不发送系统消息
     * @param question     用户输入的实际问题
     * @return LLM 生成的回复文本
     */
    fun ask(systemPrompt:String, question: String): String

    /**
     * 向 LLM 发起提问（可携带图片附件——图片通道）
     *
     * 默认实现**忽略图片**退回纯文本（测试替身等实现无需改动即可兼容）；
     * 真正发送多模态内容由 [OpenAIAPI] override——它在 JSON 边界把
     * text + image_url 组装成 OpenAI content 数组。
     *
     * @param images 图片附件（data URI 形式，见 [PromptImage]）；空列表等价于两参 [ask]
     */
    fun ask(systemPrompt: String, question: String, images: List<PromptImage>): String =
        ask(systemPrompt, question)

    /**
     * 向 LLM 发起提问（**API 原生多轮上下文**——messages 数组）
     *
     * 与两参 [ask] 的区别：[history] 里的每一轮都按 OpenAI 协议以独立的
     * `user` / `assistant` 消息随请求发出（而不是压进同一条 user 文本），
     * 模型能分清哪句是自己说的、哪句是对方说的，多轮对话/追问场景更稳。
     *
     * 默认实现**把历史折叠成一条 user 文本**（测试替身等实现无需改动即可兼容）；
     * 真正走协议 messages 数组由 [OpenAIAPI] override。
     *
     * @param systemPrompt 系统提示词（可为空；即使非空也只需出现在请求头一次，
     *   不必再塞进 [history]——重复发只会白烧 token）
     * @param history      历史轮次（按时间正序；不包含本轮 [question]）
     * @param question     本轮用户输入
     * @return LLM 生成的回复文本
     */
    fun askWithContext(systemPrompt: String, history: List<LlmTurn>, question: String): String {
        val folded = buildString {
            history.forEach { turn -> append(turn.role.wire).append("：").append(turn.content).append('\n') }
            append("user：").append(question)
        }
        return ask(systemPrompt, folded)
    }

    /**
     * 向 LLM 发起提问（便捷重载）
     *
     * 不提供系统提示词，直接将 [question] 作为用户消息发送。
     *
     * @param question 用户输入的实际问题
     * @return LLM 生成的回复文本
     */
    fun ask(question: String): String{
        return ask("", question)
    }
}

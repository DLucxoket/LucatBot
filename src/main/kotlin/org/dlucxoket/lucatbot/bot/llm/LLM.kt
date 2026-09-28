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
         */
        fun install(llm: LLM) {
            springInstance = llm
        }

        /**
         * 全局默认 LLM 实例——一个恒定的轻量委托对象。
         *
         * 设计要点（两条路径）：
         * - Spring 环境：`springInstance` 已由 LlmConfig 注册，`ask()` 转发到容器
         *   管理的 `OpenAIAPI` Bean（`@Value` 读取 application.yaml 的 llm.mimo.* 配置）。
         * - 非 Spring 环境（单元测试直接 new Agent）：`springInstance` 为 null，
         *   `ask()` 转发到 [OpenAIAPI.DEFAULT]（手 new 实例，读环境变量/系统属性）。
         *
         * 为什么是委托对象而非「每次 getter 返回当前实例」：Agent（如 LoopConfig 中的
         * ConversationAgent、MessageListController 的 SessionPickerAgent）在 Spring 启动期构造时
         * 会把 [DEFAULT] 作为默认构造参数捕获——若直接返回原始实例，捕获发生在注册之前
         * 就会永久拿到兜底实例。委托对象把解析推迟到 `ask()` 调用时，彻底消除注册顺序依赖。
         */
        val DEFAULT: LLM = object : LLM {
            override fun ask(systemPrompt: String, question: String): String =
                (springInstance ?: OpenAIAPI.DEFAULT).ask(systemPrompt, question)
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

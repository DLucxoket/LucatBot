package org.dlucxoket.lucatbot.bot.llm

import org.springframework.stereotype.Component

/**
 * 将 Spring 容器管理的 [OpenAIAPI] Bean 注册为 [LLM.DEFAULT] 的实际实现。
 *
 * 行为：
 * - 构造时调用 [LLM.install]——此时 `OpenAIAPI` 已完成 `@Value` 配置注入
 *   （application.yaml 的 llm.mimo.*），之后所有走 [LLM.DEFAULT] 的调用
 *   都会转发到这个容器 Bean。
 * - 非 Spring 环境不会加载本类，[LLM.DEFAULT] 保持 null 的 springInstance，
 *   转发到 [OpenAIAPI.DEFAULT] 兜底实例。
 *
 * 注册时机无顺序依赖：[LLM.DEFAULT] 是延迟解析的委托对象，
 * 即使 Agent 在本类之前构造，其 ask() 也会在调用时拿到已注册的容器实例。
 */
@Component
class LlmConfig(openAIAPI: OpenAIAPI) {
    init {
        LLM.install(openAIAPI)
    }
}

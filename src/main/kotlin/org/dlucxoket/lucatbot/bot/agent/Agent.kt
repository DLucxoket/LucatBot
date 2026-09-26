package org.dlucxoket.lucatbot.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.dlucxoket.lucatbot.bot.llm.LLM

/**
 * 智能体（Agent）抽象基类
 *
 * **核心设计原则**：模拟真人在 QQ 中的真实行为。
 *
 * **JSON ↔ OOP 边界约定**：
 * - LLM API 层面：输入/输出均为 JSON 字符串（LLM 天然读写文本）
 * - Agent 内部：反序列化为 OOP 对象操作（类型安全、可维护）
 * - 边界转换由 [fromJson]/[toJson] 模板方法处理，子类只需操作 OOP 对象
 *
 * @param I 输入类型（反序列化目标）
 * @param O 输出类型（序列化来源）
 * @param llm LLM 实例（依赖注入，默认全局单例）
 */
abstract class Agent<I, O>(
    /** LLM 实例——构造注入，支持测试时替换 */
    protected val llm: LLM = LLM.DEFAULT
) {
    /** Jackson ObjectMapper（Kotlin 模块，支持 data class 默认值） */
    protected val mapper: ObjectMapper = jacksonObjectMapper()

    /**
     * 智能体功能描述（必填）
     *
     * 会被 DispatcherAgent 收集，以纯文本形式展示给 LLM，供其决策是否调用。
     */
    abstract val description: String

    /**
     * 传递给 LLM 的系统提示开头
     *
     * lazy 延迟初始化——description 是 abstract val，子类构造完成后才可用
     */
    val systemPromptHead: String by lazy {
        """
        你的信息：
        $description
        ===================================
        """.trimIndent()
    }

    /** description 的首行摘要（lazy，同上） */
    val shortDescription: String by lazy { description.substringBefore("\n") }

    /**
     * 输入类型的 Class（用于 JSON 反序列化）
     */
    protected abstract val inputClass: Class<I>

    /**
     * 智能体核心处理逻辑（子类实现 OOP 部分）
     *
     * @param input OOP 输入对象（已从 JSON 反序列化）
     * @return OOP 输出对象（将被序列化为 JSON 返回）
     */
    protected abstract fun handle(input: I): O

    /**
     * JSON 边界入口（由 AgentChain 调用）
     *
     * 流程：JSON 字符串 → 反序列化为 OOP → 调用 [handle] → 序列化结果为 JSON
     *
     * @param json JSON 字符串
     * @return JSON 字符串
     */
    fun process(json: String): String {
        val input: I = fromJson(json)
        val output: O = handle(input)
        return toJson(output)
    }

    /**
     * JSON → OOP（反序列化）
     */
    protected open fun fromJson(json: String): I {
        return mapper.readValue(json, inputClass)
    }

    /**
     * OOP → JSON（序列化）
     */
    protected open fun toJson(obj: O): String {
        return mapper.writeValueAsString(obj)
    }
}

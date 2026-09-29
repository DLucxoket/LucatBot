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
 * @param llm LLM 实例——构造注入，支持测试时替换
 */
abstract class Agent<I, O>(
    /**
     * LLM 实例——构造注入，支持测试时替换。
     *
     * 默认 [LLM.DEFAULT] 是一个延迟解析的委托：非 Spring 环境（单元测试
     * 直接 new Agent）走 OpenAIAPI 兜底实例；Spring 环境下 LlmConfig 已注册
     * 容器管理的 OpenAIAPI Bean，ask() 调用会转发到该 Bean（@Value 配置生效）。
     */
    protected val llm: LLM = LLM.DEFAULT
) {
    /** Jackson ObjectMapper（Kotlin 模块，支持 data class 默认值） */
    protected val mapper: ObjectMapper = jacksonObjectMapper()

    /**
     * 智能体功能描述（必填）
     *
     * 以纯文本形式描述该智能体的职责，作为文档/调试信息保留。
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
     * JSON 边界入口（LLM API 层调用）
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
     * OOP 边界入口（Agent/Tool 链内部调用）
     *
     * 流程：Kotlin 对象 → 调用 [handle] → 返回 [O] 对象，不经过 JSON 序列化。
     * 类型由签名 [I] 保证——调用方传的本就是输入类型，无需运行时适配。
     *
     * @param input 输入对象（类型即 [I]）
     * @return 输出对象
     */
    fun processObject(input: I): O = handle(input)

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

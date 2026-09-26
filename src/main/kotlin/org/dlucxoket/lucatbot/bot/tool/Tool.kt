package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.mikuac.shiro.core.Bot

/**
 * 工具（Tool）抽象基类
 *
 * 与 [org.dlucxoket.lucatbot.bot.agent.Agent] 的区别：
 * - Agent 是 LLM 驱动的智能处理单元，可以调用 LLM 做判断和生成
 * - Tool 是确定性的数据查询/交互操作，不调用 LLM
 *
 * **JSON ↔ OOP 边界约定**（与 Agent 一致）：
 * - 调度层：输入/输出为 JSON 字符串
 * - Tool 内部：反序列化为 OOP 对象操作
 * - 边界转换由 [process] 模板方法处理，子类只需实现 [handle]
 *
 * 发现机制：通过 ClassGraph 自动扫描 Tool 子类，无需手动注册。
 *
 * @param I 输入类型
 * @param O 输出类型
 */
abstract class Tool<I, O> {
    /** 工具唯一标识名 */
    abstract val name: String

    /** LLM 可读的功能描述，用于调度器决策是否调用 */
    abstract val description: String

    /** 参数格式说明，供 LLM 理解输入格式 */
    abstract val parameterHint: String

    /** 输入类型的 Class（用于 JSON 反序列化） */
    protected abstract val inputClass: Class<I>

    /** Jackson ObjectMapper */
    protected val mapper: ObjectMapper = ObjectMapper()

    /** 绑定的 Shiro Bot 实例（用于调用 OneBot API） */
    protected var bot: Bot? = null

    /**
     * 绑定 Bot 实例
     */
    fun bindBot(bot: Bot) {
        this.bot = bot
    }

    /**
     * OOP 处理逻辑（子类实现）
     */
    protected abstract fun handle(input: I): O

    /**
     * JSON 边界入口（模板方法，由 AgentChain 调用）
     *
     * 流程：JSON → 反序列化 OOP → [handle] → 序列化 JSON
     */
    fun process(json: String): String {
        val input: I = fromJson(json)
        val output: O = handle(input)
        return toJson(output)
    }

    /**
     * 兼容旧接口：接受简单字符串参数
     * （对简单 Tool 提供便捷入口）
     */
    open fun execute(input: String): String {
        return process(input)
    }

    protected open fun fromJson(json: String): I {
        return mapper.readValue(json, inputClass)
    }

    protected open fun toJson(obj: O): String {
        return mapper.writeValueAsString(obj)
    }
}

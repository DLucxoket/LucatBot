package org.dlucxoket.lucatbot.bot.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.dlucxoket.lucatbot.bot.llm.TokenUsageScope

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
        """.trimIndent().fixIndent("            ")
    }

    /** description 的首行摘要（lazy，同上） */
    val shortDescription: String by lazy { description.substringBefore("\n") }

    /**
     * 修正 raw string 模板里 **trimIndent 被多行插值打失效** 的左 margin（**省 token**）
     *
     * 模板里插了多行值（人格 / 记忆 / 待办 / 操作日志 / 聊天记录…）时，插值续行
     * 是 0 缩进，把 `trimIndent()` 的 minIndent 拉到 0 → 整段 prompt 保留源码缩进
     * （实测会话决策 prompt **105/123 行**各带 12 空格，每轮白烧 token）。
     *
     * 判定：**首个非空行仍带 [indent]** 才说明 trimIndent 没生效，此时逐行剥掉
     * 最多一个 [indent]（模板行有、插值续行没有，正好对齐）；已生效（首行 0 缩进）
     * 则原样返回——**幂等**，两步调用也安全。
     *
     * @param indent 模板源码缩进（各 Agent 的 raw string 均为 12 空格）
     */
    protected fun String.fixIndent(indent: String): String {
        val lines = lines()
        val first = lines.firstOrNull { it.isNotBlank() } ?: return this
        if (!first.startsWith(indent)) return this
        return lines.joinToString("\n") { if (it.startsWith(indent)) it.substring(indent.length) else it }
    }

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
        val output: O = TokenUsageScope.withAgent(agentTag) { handle(input) }
        return toJson(output)
    }

    /**
     * OOP 边界入口（Agent/Tool 链内部调用）
     *
     * 流程：Kotlin 对象 → 调用 [handle] → 返回 [O] 对象，不经过 JSON 序列化。
     * 类型由签名 [I] 保证——调用方传的本就是输入类型，无需运行时适配。
     *
     * 顺带把 [agentTag] 挂进 **token 用量维度**（LLM 调用发生在 handle 里）——
     * 按 agent 分类统计就靠这一层，子类不用各自操心。
     *
     * @param input 输入对象（类型即 [I]）
     * @return 输出对象
     */
    fun processObject(input: I): O = TokenUsageScope.withAgent(agentTag) { handle(input) }

    /**
     * token 统计里的 agent 名——默认类名（`ConversationAgent` / `SessionPickerAgent` …）
     *
     * 需要更贴切的标签（如区分阶段）时子类 override 即可。
     */
    protected open val agentTag: String get() = this::class.java.simpleName

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

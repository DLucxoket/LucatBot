package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mikuac.shiro.core.Bot
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.request.PendingRequestStore

/**
 * Tool 层共享服务聚合
 *
 * Tool 由 ClassGraph 发现（多为 object 单例），无法构造注入。
 * [ToolDependencyBinder] 在 Spring 启动时构造一次本对象，
 * 逐个调用 [Tool.bindServices] 完成绑定——新增带依赖的 Tool 只需
 * override [Tool.bindServices]，无需改 Binder 的字段清单。
 *
 * @param pendingRequestStore 待处理请求队列（群邀请/加群申请/好友请求）——
 *   生产由 Binder 注入 Spring 单例；默认新实例供测试隔离（各测独立队列）
 */
data class ToolServices(
    val memoryService: MemoryService,
    val chatHistoryService: ChatHistoryService,
    val lucatBot: LucatBot,
    val pendingRequestStore: PendingRequestStore = PendingRequestStore()
)

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

    /** Jackson ObjectMapper（Kotlin 模块，支持 data class 默认值） */
    protected val mapper: ObjectMapper = jacksonObjectMapper()

    /**
     * 绑定的 Shiro Bot 实例（用于调用 OneBot API）
     *
     * 两阶段初始化：Tool 由 ClassGraph 自动发现（object 单例），
     * Bot 实例在消息到达时通过 [bindBot] 绑定。
     * 这里用 var 是运行时注入的必要设计，不是滥用可变状态。
     *
     * `@Volatile` 必不可少：写发生在 Shiro WS 消息线程（MainPlugin → ToolDependencyBinder），
     * 读发生在主循环线程（lucatbot-main-loop），无 happens-before 边界时
     * 主循环可能长期读到 null「Bot 未绑定」。
     */
    @Volatile
    protected var bot: Bot? = null

    /**
     * 绑定 Bot 实例
     */
    fun bindBot(bot: Bot) {
        this.bot = bot
    }

    /**
     * 绑定应用服务依赖（启动时由 [ToolDependencyBinder] 自动遍历调用）
     *
     * 默认空实现：无服务依赖的 Tool 无需关心。
     * 需要依赖的 Tool override 本方法，把引用存到自己的 private 字段，
     * 对外只暴露语义方法。
     */
    open fun bindServices(services: ToolServices) {}

    /**
     * OOP 处理逻辑（子类实现）
     */
    protected abstract fun handle(input: I): O

    /**
     * 统一「取 Bot + 异常转错误结果」模板——消掉各 Tool 手写的
     * `val b = bot ?: return XResult(success = false, error = "Bot 未绑定")`
     * + `return try { … } catch (e: Exception) { XResult(…) }` 样板
     *
     * 语义（迁移逐点保持等价）：
     * 1. `bot == null` → 返回 `onError("Bot 未绑定")`（迁移点原文案即此）；
     * 2. [block] 正常执行 → 其结果原样透出；
     * 3. [block] 抛异常 → 返回 `onError("异常简单类名: 异常消息")`——
     *    **统一错误文案**（此前混用裸 `e.message` 与带类名两种写法），
     *    与 [ToolCatalog] 报错 JSON 同格式。
     *
     * 用法：[block] 内需要提前退出（参数校验失败等）时写
     * `return@runTool 结果`（lambda 局部返回，语义与原手写 `return` 一致）；
     * [crossinline] 禁止的只是从 `handle` 里"跳出去"的非局部 return。
     *
     * 错误契约：[onError] 构造的结果必须 `success = false`
     * （`ToolErrorContractTest` 全量守卫，见 AGENTS.md）。
     */
    protected inline fun <T> runTool(
        crossinline onError: (String) -> T,
        crossinline block: (Bot) -> T
    ): T {
        val b = bot ?: return onError("Bot 未绑定")
        return try {
            block(b)
        } catch (e: Exception) {
            onError("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 无 Bot 依赖工具的「异常转错误结果」兜底（判空类依赖保留手写——
     * 各服务未绑定文案不同：ChatHistoryService / MemoryService 等）
     *
     * 语义：[block] 正常 → 结果原样透出；抛异常 → `onError("异常简单类名: 异常消息")`，
     * 与 [runTool] 同一统一文案。提前退出写 `return@tryResult 结果`。
     *
     * 错误契约同 [runTool]：[onError] 构造的结果必须 `success = false`。
     */
    protected inline fun <T> tryResult(
        crossinline onError: (String) -> T,
        crossinline block: () -> T
    ): T = try {
        block()
    } catch (e: Exception) {
        onError("${e.javaClass.simpleName}: ${e.message}")
    }

    /**
     * JSON 边界入口（模板方法，LLM API 层调用）
     *
     * 流程：JSON → 反序列化 OOP → [handle] → 序列化 JSON
     */
    fun process(json: String): String {
        val input: I = fromJson(json)
        val output: O = handle(input)
        return toJson(output)
    }

    /**
     * OOP 边界入口（Agent/Tool 链内部调用）
     *
     * 流程：Kotlin 对象 → [handle] → 返回 [O] 对象，不经过 JSON 序列化。
     * 类型由签名 [I] 保证——调用方传的本就是输入类型，无需运行时适配。
     */
    fun processObject(input: I): O = handle(input)

    protected open fun fromJson(json: String): I {
        return mapper.readValue(json, inputClass)
    }

    protected open fun toJson(obj: O): String {
        return mapper.writeValueAsString(obj)
    }
}

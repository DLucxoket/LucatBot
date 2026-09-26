package org.dlucxoket.lucatbot.bot.agent

import io.github.classgraph.ClassGraph
import org.dlucxoket.lucatbot.bot.tool.Tool
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubclassOf

/**
 * 智能体调度器（DispatcherAgent）
 *
 * **模拟真人的决策过程**：像人看一眼消息列表，决定点进哪个会话。
 *
 * 核心职责：根据当前上下文，自动决定接下来应调用哪些 Agent，并生成调用计划。
 *
 * LLM 输出格式（JSON）：
 * ```json
 * [{"agent": "AgentName", "input": {...}}, ...]
 * ```
 * 或 `"done"` 表示终止。
 *
 * 输入/输出均为 JSON 字符串（LLM API 边界）。
 * 注意：这是一个 Kotlin `object`（单例）。
 */
object DispatcherAgent : Agent<String, String>() {
    /**
     * 调度器的功能描述
     *
     * 这段描述会被直接放进 LLM 的系统提示中，指导 LLM：
     * - 输出格式：每行 "AgentName: 输入"，不要空行
     * - 只调用确实需要的 Agent，不必全部调用
     * - 若无法准确完成任务的 Agent，不要调用它
     * - 无需继续调用时返回 "done"
     */
    override val description: String = """
        一个Agent调度器，模拟真人决定接下来做什么。
        根据上下文决定调用哪些Agent，输出JSON格式的调用计划：
        [{"agent": "AgentName", "input": {...}}]
        或者返回"done"表示不需要再调用任何Agent。
        
        约束：
        - input由你显式指定，只传该Agent需要的信息
        - 每行一个调用，没有空行
        - 不需要就返回done
    """.trimIndent()

    override val inputClass: Class<String> = String::class.java

    /**
     * JSON 边界入口：直接将 LLM 输出作为结果
     * （DispatcherAgent 的输出本身就是给 LLM 解析的调用计划）
     */
    override fun handle(input: String): String {
        return processDispatcher(input)
    }

    /**
     * 根据上下文，决策下一步应调用哪些 Agent
     *
     * @param input 用户输入的上下文 JSON
     * @return LLM 生成的调用计划 JSON
     */
    private fun processDispatcher(input: String): String {
        /**
         * 第一步：使用 ClassGraph 扫描所有 Agent 子类
         *
         * 过滤条件：
         * - 排除抽象类和接口（只保留可实例化的具体类）
         * - 排除 DispatcherAgent 自身（避免自调用死循环）
         * - 通过 Kotlin 反射确认是 Agent 子类后，转换为 KClass<out Agent>
         */
        val agentClasses: List<KClass<out Agent<*, *>>> = ClassGraph()
            .enableClassInfo()
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(Agent::class.java.name)
                    .filter { !it.isAbstract && !it.isInterface }
                    .mapNotNull {
                        val kClass = it.loadClass().kotlin
                        if (kClass.isSubclassOf(Agent::class)) {
                            @Suppress("UNCHECKED_CAST")
                            kClass as KClass<out Agent<*, *>>
                        } else {
                            null
                        }
                    }
                    .filter { it != this::class }
            }

        /**
         * 第二步：遍历所有 Agent 子类，获取实例并提取简要描述
         *
         * 格式："- AgentName: 短描述"，多行拼接成一个字符串供 LLM 阅读
         */
        val sb = StringBuilder()
        agentClasses.forEach { kClass ->
            val instance = getInstanceOf(kClass)
            sb.append("\n- ${kClass.simpleName}: ${instance.shortDescription}")
        }

        // 扫描 Tool 类，一并展示给 LLM
        val toolClasses = ClassGraph()
            .enableClassInfo()
            .scan()
            .use { scanResult ->
                scanResult.getSubclasses(Tool::class.java.name)
                    .filter { !it.isAbstract && !it.isInterface }
                    .mapNotNull { it.loadClass().kotlin }
            }

        sb.appendLine()
        sb.appendLine("可用的Tool（数据查询工具，输入由你指定）：")
        toolClasses.forEach { kClass ->
            val instance = kClass.objectInstance ?: kClass.createInstance()
            if (instance is Tool) {
                sb.appendLine("- ${instance.name}: ${instance.description}  参数格式：${instance.parameterHint}")
            }
        }

        /**
         * 第三步：调用 LLM 进行智能决策
         *
         * 提示词结构：
         * - systemPromptHead：调度器自身身份说明
         * - 可用 Agent 列表：所有被发现的 Agent 及其短描述
         * - 上下文：当前输入的原始内容
         *
         * LLM 返回格式为调用计划文本，原样返回给调用方解析执行。
         */
        val reply = llm.ask("""
            $systemPromptHead

            可用的Agent以及描述：$sb

            上下文：
            $input

            请输出JSON格式的调用计划，例如：
            [{"agent": "ReplyAgent", "input": {"context": "...", "messages": [...]}}]
            或者直接输出 done 表示不需要再调用。
        """.trimIndent())

        return reply
    }

    /**
     * 获取 Agent 类的实例
     *
     * 优先返回 Kotlin `object` 单例（objectInstance），
     * 否则通过无参构造器创建新实例（createInstance）。
     *
     * @param kClass Agent 的 KClass
     * @return 该类的实例
     */
    private fun getInstanceOf(kClass: KClass<out Agent<*, *>>): Agent<*, *> {
        return kClass.objectInstance ?: kClass.createInstance()
    }
}

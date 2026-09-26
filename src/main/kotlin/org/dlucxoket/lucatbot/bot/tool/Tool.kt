package org.dlucxoket.lucatbot.bot.tool

import com.mikuac.shiro.core.Bot

/**
 * 工具（Tool）抽象基类
 *
 * 与 [org.dlucxoket.lucatbot.bot.agent.Agent] 的区别：
 * - Agent 是 LLM 驱动的智能处理单元，可以调用 LLM 做判断和生成
 * - Tool 是确定性的数据查询/交互操作，不调用 LLM
 *
 * Tool 负责与 OneBot 协议和 model 层交互：
 * 查询聊天记录、查看用户主页、获取消息列表等。
 *
 * 发现机制：与 Agent 相同，通过 ClassGraph 自动扫描 Tool 子类。
 */
abstract class Tool {
    /** 工具唯一标识名 */
    abstract val name: String

    /** LLM 可读的功能描述，用于调度器决策是否调用 */
    abstract val description: String

    /** 参数格式说明，供 LLM 理解输入格式 */
    abstract val parameterHint: String

    /** 绑定的 Shiro Bot 实例（用于调用 OneBot API） */
    protected var bot: Bot? = null

    /**
     * 绑定 Bot 实例
     *
     * 在消息处理入口处调用，使 Tool 可以访问 OneBot API。
     */
    fun bindBot(bot: Bot) {
        this.bot = bot
    }

    /**
     * 执行工具
     *
     * @param input 参数字符串，格式由 [parameterHint] 定义
     * @return 执行结果文本
     */
    abstract fun execute(input: String): String
}

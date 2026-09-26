package org.dlucxoket.lucatbot.adapter.onebot

import com.mikuac.shiro.core.Bot
import com.mikuac.shiro.core.BotPlugin
import com.mikuac.shiro.dto.event.message.AnyMessageEvent
import com.mikuac.shiro.dto.event.request.FriendAddRequestEvent
import org.dlucxoket.lucatbot.bot.agent.AgentChain
import org.dlucxoket.lucatbot.bot.tool.ChatHistoryTool
import org.dlucxoket.lucatbot.bot.tool.MessageListTool
import org.dlucxoket.lucatbot.bot.tool.UserProfileTool
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Shiro 主插件——OneBot 消息处理入口
 *
 * 所有 QQ 消息事件的第一入口。通过 application.yaml 中的
 * `shiro.plugin-list` 配置注册，Shiro 框架会在收到事件时按序调用。
 *
 * 消息处理链路：
 * 1. ShiroConverter 将 Shiro 事件转为 model 层对象
 * 2. 提取纯文本作为 AgentChain 的初始输入
 * 3. AgentChain 调度执行（DispatcherAgent → Agent 并行 → 循环决策）
 * 4. 将最终结果回复给用户
 *
 * 插件链机制：
 * - 返回 MESSAGE_IGNORE → 继续执行 plugin-list 中的下一个插件
 * - 返回 MESSAGE_BLOCK  → 中断插件链，不执行后续插件
 */
@Component
internal class MainPlugin: BotPlugin() {
    private val log = LoggerFactory.getLogger(MainPlugin::class.java)

    /** Agent 调度执行引擎 */
    private val agentChain = AgentChain()

    /**
     * 处理所有类型的消息事件（群消息、私聊消息等）
     *
     * 完整链路：消息接收 → Model 转换 → AgentChain 调度 → 回复。
     *
     * @param bot   Shiro 的 Bot 实例，可用于发送消息、获取群成员等操作
     * @param event 消息事件，包含发送者、消息内容、来源群/私聊等信息
     * @return MESSAGE_BLOCK 阻断后续插件（已处理）
     */
    override fun onAnyMessage(bot: Bot, event: AnyMessageEvent): Int {
        try {
            // 绑定 Bot 实例到所有 Tool
            bindTools(bot)

            // 转为 model 层对象（供 Agent 系统使用）
            val modelEvent = ShiroConverter.toModelEvent(event)

            // 提取纯文本作为 AgentChain 初始输入
            val plainText = ShiroConverter.extractPlainText(event.message)
            if (plainText.isBlank()) {
                log.info("收到空文本消息，跳过处理")
                return MESSAGE_IGNORE
            }

            log.info("收到消息: $plainText")

            // 执行 Agent 调度链
            val reply = agentChain.execute(plainText)

            // 回复消息
            bot.sendMsg(event.messageType, event.userId, event.groupId, reply)
            log.info("回复消息: $reply")

            return MESSAGE_BLOCK
        } catch (e: Exception) {
            log.error("消息处理异常: ${e.message}", e)
            return MESSAGE_IGNORE
        }
    }

    /**
     * 处理好友添加请求
     *
     * 自动通过所有好友请求。
     *
     * @param bot   Bot 实例，用于调用 setFriendAddRequest 响应请求
     * @param event 好友添加请求事件
     * @return 调用 super 实现
     */
    override fun onFriendAddRequest(bot: Bot, event: FriendAddRequestEvent): Int {
        bot.setFriendAddRequest(event.flag, true, null)
        return super.onFriendAddRequest(bot, event)
    }

    /**
     * 绑定 Bot 实例到所有 Tool
     *
     * Tool 需要 Bot 来调用 OneBot API。
     */
    private fun bindTools(bot: Bot) {
        ChatHistoryTool.bindBot(bot)
        UserProfileTool.bindBot(bot)
        MessageListTool.bindBot(bot)
    }
}

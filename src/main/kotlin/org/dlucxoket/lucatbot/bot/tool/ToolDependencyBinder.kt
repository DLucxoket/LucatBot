package org.dlucxoket.lucatbot.bot.tool

import jakarta.annotation.PostConstruct
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Tool 依赖绑定器
 *
 * Tool 由 ClassGraph 发现（多为 Kotlin object 单例），无法走构造注入。
 * Spring 启动时把服务依赖聚合成一个 [ToolServices]，遍历所有已发现的 Tool
 * 调用 [Tool.bindServices] 自动绑定——新增带依赖的 Tool 只需 override
 * bindServices，无需改这里的字段清单，也不会漏绑。
 *
 * Bot 实例在消息到达时由 [bindBot] 绑定（见 MainPlugin）。
 */
@Component
class ToolDependencyBinder(
    private val memoryService: MemoryService,
    private val chatHistoryService: ChatHistoryService,
    private val lucatBot: LucatBot
) {
    private val log = LoggerFactory.getLogger(ToolDependencyBinder::class.java)

    @PostConstruct
    fun bindServices() {
        val services = ToolServices(
            memoryService = memoryService,
            chatHistoryService = chatHistoryService,
            lucatBot = lucatBot
        )
        // 与发现/调用共享同一批实例（ToolDiscovery 单例列表）
        val tools = ToolDiscovery.getTools()
        tools.forEach { it.bindServices(services) }
        log.info("Tool 服务依赖绑定完成（{} 个 Tool）", tools.size)
    }

    companion object {
        /** 上次绑定的 Bot 实例——同实例重复绑定直接短路（每条消息都会调到这里） */
        @Volatile
        private var lastBoundBot: com.mikuac.shiro.core.Bot? = null

        /**
         * 把 Bot 实例绑到所有已发现的 Tool（含未来新增）。
         *
         * 引用相同（单账号正常路径）时直接返回，避免每条消息遍历全部
         * Tool 类重复写同一引用；换 Bot（重连/切账号）时才重新绑定。
         */
        fun bindBotToAll(bot: com.mikuac.shiro.core.Bot) {
            if (lastBoundBot === bot) return
            ToolDiscovery.getTools().forEach { it.bindBot(bot) }
            lastBoundBot = bot
        }
    }
}

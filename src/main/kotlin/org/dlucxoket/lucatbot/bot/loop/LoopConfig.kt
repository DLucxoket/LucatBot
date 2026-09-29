package org.dlucxoket.lucatbot.bot.loop

import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.agent.ConversationAgent
import org.dlucxoket.lucatbot.bot.agent.SessionPickerAgent
import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.dlucxoket.lucatbot.bot.tool.ChatHistoryTool
import org.dlucxoket.lucatbot.bot.tool.SendMessageRequest
import org.dlucxoket.lucatbot.bot.tool.SendMessageResult
import org.dlucxoket.lucatbot.bot.tool.SendMessageTool
import org.dlucxoket.lucatbot.bot.tool.Tool
import org.dlucxoket.lucatbot.bot.tool.ToolCatalog
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.model.session.SessionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 主循环编排层的 Bean 装配
 *
 * 编排类本身不加 @Component，由这里集中构造，便于测试时替换 Agent/Tool。
 */
@Configuration
class LoopConfig {

    @Bean
    fun actionLog(): ActionLog = ActionLog()

    /**
     * 工具目录与调用网关——两个主智能体 list_tools / tool 动作的执行层。
     *
     * 默认从 ToolDiscovery 单例列表取工具（与发现/绑定共享同一批实例）。
     *
     * [ToolCatalog.orchestrationOnly] 排除策略：SendMessageTool 的调用
     * 必须走 send 动作（编排层负责写短期记忆、破冰状态、连发提示与失败
     * 离场），经 tool 直调会绕过全部记账——故不进目录、不可被调用。
     */
    @Bean
    fun toolCatalog(): ToolCatalog = ToolCatalog(
        orchestrationOnly = setOf("SendMessageTool")
    )

    @Bean
    fun messageListController(
        lucatBot: LucatBot,
        sessionManager: SessionManager,
        memoryService: MemoryService,
        chatHistoryService: ChatHistoryService,
        todoService: TodoService,
        actionLog: ActionLog,
        toolCatalog: ToolCatalog
    ): MessageListController = MessageListController(
        lucatBot = lucatBot,
        sessionManager = sessionManager,
        memoryService = memoryService,
        chatHistoryService = chatHistoryService,
        todoService = todoService,
        actionLog = actionLog,
        sessionPickerAgent = SessionPickerAgent(),
        toolCatalog = toolCatalog
    )

    @Bean
    fun conversationController(
        lucatBot: LucatBot,
        sessionManager: SessionManager,
        memoryService: MemoryService,
        chatHistoryService: ChatHistoryService,
        todoService: TodoService,
        actionLog: ActionLog,
        toolCatalog: ToolCatalog
    ): ConversationController = ConversationController(
        lucatBot = lucatBot,
        sessionManager = sessionManager,
        memoryService = memoryService,
        chatHistoryService = chatHistoryService,
        todoService = todoService,
        actionLog = actionLog,
        conversationAgent = ConversationAgent(),
        chatHistoryTool = ChatHistoryTool as Tool<ChatHistoryRequest, ChatHistoryResult>,
        sendMessageTool = SendMessageTool as Tool<SendMessageRequest, SendMessageResult>,
        toolCatalog = toolCatalog
    )
}

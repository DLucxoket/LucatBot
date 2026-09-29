package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.memory.MemoryService
import org.dlucxoket.lucatbot.bot.memory.MemorySubject
import org.dlucxoket.lucatbot.bot.tool.data.MemoryQueryRequest
import org.dlucxoket.lucatbot.bot.tool.data.MemoryQueryResult

/**
 * 记忆查询工具
 *
 * 让 Agent 系统能查询机器人的记忆。
 * JSON 边界：`MemoryQueryRequest` ↔ `MemoryQueryResult`
 */
object MemoryQueryTool : Tool<MemoryQueryRequest, MemoryQueryResult>() {
    override val name: String = "MemoryQueryTool"

    override val description: String = "功能：查询长期记忆：传 userId 返回该用户的全部记忆，否则按 query 关键词搜索，返回命中的记忆文本列表。\n何时使用：prompt 只自动注入主体记忆与少量其他会话背景——需要注入里没有的更早/更具体记忆（约定、名字细节、跨会话旧事）时按关键词调用；答不上来之前先搜一次，搜不到再如实说不记得。"

    override val parameterHint: String = "JSON: {\"query\": \"关键词\", \"userId\": 123456}"

    override val inputClass: Class<MemoryQueryRequest> = MemoryQueryRequest::class.java

    /** 记忆服务引用（启动时经 [bindServices] 注入，不对外暴露） */
    @Volatile
    private var memoryService: MemoryService? = null

    override fun bindServices(services: ToolServices) {
        memoryService = services.memoryService
    }

    override fun handle(input: MemoryQueryRequest): MemoryQueryResult {
        val service = memoryService ?: return MemoryQueryResult(success = false, error = "MemoryService 未绑定")

        return try {
            val results = if (input.userId != null) {
                service.getMemoriesOf(MemorySubject.user(input.userId))
            } else {
                service.search(input.query)
            }
            MemoryQueryResult(
                memories = results.map { it.content },
                success = true
            )
        } catch (e: Exception) {
            MemoryQueryResult(success = false, error = e.message)
        }
    }
}

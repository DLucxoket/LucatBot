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

    override val description: String = "查询机器人的记忆。输入关键词或用户QQ号，返回相关的记忆内容。"

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

        val results = if (input.userId != null) {
            service.getMemoriesOf(MemorySubject.user(input.userId))
        } else {
            service.search(input.query)
        }

        return MemoryQueryResult(
            memories = results.map { it.content },
            success = true
        )
    }
}

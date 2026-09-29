package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.bot.tool.data.HistoryMessageItem

/**
 * 聊天记录查询工具
 *
 * 优先从本地存储读取；Bot 已绑定时会尝试向 OneBot 拉取历史（爬楼），
 * **拉到的消息一律写入 [ChatHistoryService] 持久化**，再返回合并后的列表。
 *
 * JSON 边界：`{"targetId": 123, "count": 10, "type": "private"}` ↔ `ChatHistoryRequest`
 */
object ChatHistoryTool : Tool<ChatHistoryRequest, ChatHistoryResult>() {
    override val name: String = "ChatHistoryTool"

    override val description: String = "查询聊天记录。返回指定会话的最近消息列表。"

    override val parameterHint: String = "JSON: {\"targetId\": 123456, \"count\": 10, \"type\": \"group\"或\"private\"}"

    override val inputClass: Class<ChatHistoryRequest> = ChatHistoryRequest::class.java

    private val log = org.slf4j.LoggerFactory.getLogger(ChatHistoryTool::class.java)

    /** 聊天记录服务引用（启动时经 [bindServices] 注入，不对外暴露） */
    @Volatile
    private var chatHistoryService: ChatHistoryService? = null

    override fun bindServices(services: ToolServices) {
        chatHistoryService = services.chatHistoryService
    }

    override fun handle(input: ChatHistoryRequest): ChatHistoryResult {
        val service = chatHistoryService ?: return ChatHistoryResult(
            targetId = input.targetId, success = false, error = "ChatHistoryService 未绑定"
        )

        return try {
            val conversationId = "${input.type}:${input.targetId}"

            // 爬楼：能拉远端就拉，拉到就落盘
            fetchRemoteAndPersist(service, conversationId, input)

            val records = service.getHistory(conversationId, input.count)

            ChatHistoryResult(
                targetId = input.targetId,
                messages = records.map {
                    HistoryMessageItem(
                        sender = it.senderName,
                        content = it.content,
                        timestamp = it.timestamp
                    )
                },
                success = true
            )
        } catch (e: Exception) {
            ChatHistoryResult(targetId = input.targetId, success = false, error = e.message)
        }
    }

    /**
     * 从 OneBot 拉取历史消息并写入本地聊天记录（去重靠稳定 id）
     */
    private fun fetchRemoteAndPersist(
        service: ChatHistoryService,
        conversationId: String,
        input: ChatHistoryRequest
    ) {
        val b = bot ?: return
        val isGroup = input.type == "group"
        val targetId = input.targetId

        try {
            val actionData = if (isGroup) {
                b.getGroupMsgHistory(targetId, null, input.count, true)
            } else {
                b.getFriendMsgHistory(targetId, null, input.count, true)
            }
            // 失败响应（data=null）不能静默：Shiro 的 DTO 拿不到 wording，
            // 但 status/retcode 足够定位（如 NapCat 端 uin→uid 解析失败）
            val data = actionData?.data
            if (data == null) {
                log.warn(
                    "远端历史拉取失败（本地记录不受影响）status={} retcode={} target={} type={}",
                    actionData?.status, actionData?.retCode, targetId, input.type
                )
                return
            }
            val messages = data.messages ?: return
            val selfId = b.selfId

            messages.forEach { msg ->
                val raw = msg.message ?: return@forEach
                if (raw.isBlank()) return@forEach
                // 远端爬楼：CQ 码转成与 ChatMessage.describe 同风格的富文本（禁止 base64 全量）
                var content = org.dlucxoket.lucatbot.adapter.onebot.ShiroConverter.describeRawMessage(raw)
                if (content.isBlank()) return@forEach
                // 过长内容（如合并转发 JSON）截断，避免污染 prompt / 存储
                if (content.length > 800) {
                    content = content.take(800) + "…"
                }

                val senderId = msg.sender?.userId?.toLongOrNull()
                    ?: msg.userId
                    ?: 0L
                val senderName = msg.sender?.card?.takeIf { it.isNotBlank() }
                    ?: msg.sender?.nickname?.takeIf { it.isNotBlank() }
                    ?: if (senderId == selfId) "bot" else senderId.toString()

                val rawTime = msg.time ?: 0L
                val timestamp = when {
                    rawTime <= 0L -> System.currentTimeMillis()
                    rawTime < 1_000_000_000_000L -> rawTime * 1000L  // 秒级时间戳
                    else -> rawTime
                }

                service.recordHistoryMessage(
                    conversationId = conversationId,
                    senderId = senderId,
                    senderName = senderName,
                    content = content,
                    timestamp = timestamp,
                    isFromBot = senderId == selfId
                )
            }
        } catch (e: Exception) {
            // 远端失败不影响本地读取
            log.warn("爬楼拉取远端历史失败: {}", e.message)
        }
    }
}

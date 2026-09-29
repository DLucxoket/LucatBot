package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.chathistory.ChatHistoryService
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryRequest
import org.dlucxoket.lucatbot.bot.tool.data.ChatHistoryResult
import org.dlucxoket.lucatbot.bot.tool.data.HistoryMessageItem
import org.dlucxoket.lucatbot.model.message.CqParser

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

    override val description: String = "功能：查询指定会话的最近聊天记录，先尝试向 OneBot 爬楼拉取远端历史（拉到的消息自动落库去重），再从本地存储读取，返回发送者、内容、时间的列表。\n何时使用：进入会话时编排层已自动调本工具拉最近 10 条、read_more 动作也自动按条数翻历史，常规阅读无需手动调用；仅当要查看其它会话、需要比默认更多更早的历史，或想主动触发一次爬楼落库时才调用。"

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
                        timestamp = it.timestamp,
                        senderId = it.senderId,
                        messageId = it.messageId,
                        nickname = it.senderNickname,
                        isFromBot = it.isFromBot
                    )
                },
                success = true
            )
        } catch (e: Exception) {
            ChatHistoryResult(targetId = input.targetId, success = false, error = e.message)
        }
    }

    /**
     * 统一的爬楼消息视图（两条拉取路径归一）
     *
     * @param messageId 消息 id（类型化回退路径恒为 null → 落 `hist:` 兜底 id）
     * @param raw 原始 CQ 字符串（array 形态的 message 已转 CQ）
     */
    private data class CrawlMsg(
        val messageId: Long?,
        val time: Long?,
        val userId: Long?,
        val senderUserId: Long?,
        val nickname: String?,
        val card: String?,
        val raw: String?
    )

    /**
     * 从 OneBot 拉取历史消息并写入本地聊天记录
     *
     * **id 统一为 `msg:` 风格**：主路径（自定义 DTO）拿到 message_id 后与
     * 实时入档共享稳定 id `msg:<会话>:<id>`——同一物理消息靠同一主键 upsert
     * 合一，不再产生孪生双份；仅回退路径（Shiro 类型化，DTO 无 id 字段）落
     * `hist:` 兜底 id，由读取 fold 与启动清理收敛。
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
            val messages = fetchViaCustom(b, isGroup, targetId, input.count)
                ?: fetchViaTyped(b, isGroup, targetId, input.count)
            if (messages.isEmpty()) return
            val selfId = b.selfId

            messages.forEach { msg ->
                val raw = msg.raw ?: return@forEach
                if (raw.isBlank()) return@forEach
                // 远端爬楼：CQ 码转成与 ChatMessage.describe 同风格的富文本（禁止 base64 全量）
                var content = CqParser.describe(raw)
                if (content.isBlank()) return@forEach
                // 过长内容（如合并转发 JSON）截断，避免污染 prompt / 存储
                if (content.length > 800) {
                    content = content.take(800) + "…"
                }

                val senderId = msg.senderUserId ?: msg.userId ?: 0L
                // 展示名规则与实时入档一致：群聊群名片优先、私聊只用 QQ 昵称；
                // QQ 昵称另存（senderNickname），供 prompt 注解「名片（QQ昵称 xx）」
                val qqNickname = msg.nickname?.takeIf { it.isNotBlank() }
                val card = if (isGroup) msg.card?.takeIf { it.isNotBlank() } else null
                val senderName = card
                    ?: qqNickname
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
                    isFromBot = senderId == selfId,
                    senderNickname = qqNickname,
                    messageId = msg.messageId
                )
            }
        } catch (e: Exception) {
            // 远端失败不影响本地读取
            log.warn("爬楼拉取远端历史失败: {}", e.message)
        }
    }

    /**
     * 主路径：`customRequest` + 自定义 DTO——**能拿到 message_id**
     *
     * Shiro 类型化路径（[com.mikuac.shiro.dto.action.response.MsgResp] 继承
     * MessageEvent）没有 id 字段，故绕过它自解 JSON。参数键与 Shiro 内部
     * 构造保持一致（group_id/user_id、count、reverseOrder）。
     *
     * @return null = 失败（调用方回退类型化路径）；空列表 = 成功但没消息
     */
    private fun fetchViaCustom(
        b: com.mikuac.shiro.core.Bot,
        isGroup: Boolean,
        targetId: Long,
        count: Int
    ): List<CrawlMsg>? = try {
        val params = mutableMapOf<String, Any>(
            "count" to count,
            "reverseOrder" to true
        )
        params[if (isGroup) "group_id" else "user_id"] = targetId
        val path = if (isGroup) {
            com.mikuac.shiro.enums.ActionPathEnum.GET_GROUP_MSG_HISTORY
        } else {
            com.mikuac.shiro.enums.ActionPathEnum.GET_FRIEND_MSG_HISTORY
        }
        val messages = b.customRequest(path, params, OneBotHistoryData::class.java)
            ?.data?.messages
            ?.map { it.toCrawlMsg() }
        if (messages != null && messages.isNotEmpty() && messages.all { it.messageId == null }) {
            log.warn("爬楼响应缺少 message_id（字段结构与预期不符？）——本批记录将落 hist: 兜底 id")
        }
        messages
    } catch (e: Exception) {
        log.debug("自定义历史接口失败，回退 Shiro 类型化路径: {}", e.message)
        null
    }

    /** 回退路径：Shiro 类型化（MsgResp 无 message_id → 本批记录落 `hist:` 兜底 id） */
    private fun fetchViaTyped(
        b: com.mikuac.shiro.core.Bot,
        isGroup: Boolean,
        targetId: Long,
        count: Int
    ): List<CrawlMsg> {
        val actionData = if (isGroup) {
            b.getGroupMsgHistory(targetId, null, count, true)
        } else {
            b.getFriendMsgHistory(targetId, null, count, true)
        }
        // 失败响应（data=null）不能静默：Shiro 的 DTO 拿不到 wording，
        // 但 status/retcode 足够定位（如 NapCat 端 uin→uid 解析失败）
        val data = actionData?.data
        if (data == null) {
            log.warn(
                "远端历史拉取失败（本地记录不受影响）status={} retcode={} target={}",
                actionData?.status, actionData?.retCode, targetId
            )
            return emptyList()
        }
        return data.messages.orEmpty().map { msg ->
            CrawlMsg(
                messageId = null,
                time = msg.time,
                userId = msg.userId,
                senderUserId = msg.sender?.userId?.toLongOrNull(),
                nickname = msg.sender?.nickname,
                card = msg.sender?.card,
                raw = msg.message
            )
        }
    }

    /** 自定义 DTO → 统一视图（array 形态 message 转 CQ 字符串，与 Shiro 行为一致） */
    private fun OneBotHistoryMsg.toCrawlMsg(): CrawlMsg = CrawlMsg(
        messageId = messageId,
        time = time,
        userId = userId,
        senderUserId = sender?.userId,
        nickname = sender?.nickname,
        card = sender?.card,
        raw = rawCqString(message)
    )

    private fun rawCqString(node: tools.jackson.databind.JsonNode?): String? = when {
        node == null -> null
        node.isTextual -> node.asText()
        node.isArray -> runCatching {
            com.mikuac.shiro.common.utils.MessageConverser.arraysToString(
                com.mikuac.shiro.common.utils.JsonUtils.parseArray(node, com.mikuac.shiro.model.ArrayMsg::class.java)
            )
        }.getOrNull()
        else -> null
    }
}

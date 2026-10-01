package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona
import org.dlucxoket.lucatbot.model.message.OutgoingMember

/**
 * 会话内动作
 */
enum class ConversationAction {
    /** 爬楼：再看更早的历史 */
    READ_MORE,

    /** 发一条消息 */
    SEND,

    /** 在会话里停留一会儿，到时后重新决策 */
    STAY,

    /** 退出本会话，回到消息列表 */
    RETURN,

    /** 查看/搜索工具目录（全量或按关键词） */
    LIST_TOOLS,

    /** 调用一个工具（params.name + params.input） */
    CALL_TOOL,

    /**
     * 查看图片内容（params.refs = 占位符里的文件标签）——**按需供图**：
     * 图片默认不附内容（省 token），LLM 显式声明要看哪几张，
     * 编排层取图后附到**下一轮**请求（与 list_tools 的「结果进下一轮」对称）
     */
    VIEW_IMAGES
}

/**
 * 会话内决策结果（统一信封的 OOP 形态）
 *
 * @param action 动作
 * @param params 工具入参：send → content；read_more → count；stay → seconds
 * @param reason 内心动机（不会发出去）
 * @param remember 值得长期记住的一句话（可选，写入短期记忆）
 * @param todo_add 新增待办内容（可选）
 * @param todo_done 完成待办的 id 或内容片段（可选）
 */
data class ConversationDecision(
    val action: ConversationAction,
    val params: Map<String, Any?> = emptyMap(),
    val reason: String? = null,
    @JsonProperty("remember")
    val remember: String? = null,
    @JsonProperty("todo_add")
    val todo_add: String? = null,
    @JsonProperty("todo_done")
    val todo_done: String? = null
) {
    /** send 的正文——仅旧字符串写法（`"content": "纯文本"`）时非 null；数组写法为 null */
    val content: String? get() = params["content"] as? String

    /**
     * send 的分段解析（**JSON → OOP 映射**，LLM 边界的延伸）
     *
     * 两种写法：
     * - 旧：`"content": "纯文本"` → 单条（text 走富标记兼容路径）
     * - 新：`"content": [消息对象…]` → 逐条映射（条级 reply/delay + members 结构化段）
     *
     * 坏值容错：缺字段/类型不符按可选处理（reply 解析不出 → null、delay 非数 → 0、
     * 未知 member type 保留交 Tool 丢弃）；完全无法识别的条目丢弃。
     * 全部丢光返回空列表——编排层发空会被 Tool 判「消息内容为空」失败，自然兜底。
     */
    fun resolveOutgoing(): List<OutgoingMessage> {
        return when (val c = params["content"]) {
            is String -> if (c.isBlank()) emptyList() else listOf(OutgoingMessage(text = c))
            is List<*> -> c.mapNotNull { element ->
                val m = element as? Map<*, *> ?: return@mapNotNull null
                val members = (m["members"] as? List<*>)?.mapNotNull { raw ->
                    val mm = raw as? Map<*, *> ?: return@mapNotNull null
                    OutgoingMember(
                        type = mm["type"]?.toString()?.trim()?.lowercase(),
                        text = mm["text"]?.toString(),
                        qq = mm["qq"]?.toString()?.trim(),
                        id = mm["id"]?.toString()?.trim()
                    ).takeIf { it.type != null }
                } ?: emptyList()
                OutgoingMessage(
                    replyToId = when (val r = m["reply"]) {
                        is Number -> r.toLong()
                        is String -> r.trim().toLongOrNull()
                        else -> null
                    },
                    delaySeconds = when (val d = m["delay"]) {
                        is Number -> d.toDouble()
                        is String -> d.trim().toDoubleOrNull() ?: 0.0
                        else -> 0.0
                    },
                    members = members
                ).takeIf { it.members.isNotEmpty() || it.replyToId != null }
            }
            else -> emptyList()
        }
    }

    /** read_more 的条数（params.count，默认 10） */
    val readCount: Int get() = (params["count"] as? Number)?.toInt() ?: 10

    /** stay 的停留秒数（params.seconds，默认 30；执行侧会再钳制到 1~1800） */
    val staySeconds: Int get() = (params["seconds"] as? Number)?.toInt() ?: 30

    /**
     * send 的「等回信」秒数（params.wait_message，默认 0=发完立即再次决策）
     *
     * 发送成功后原地等新消息：期间来消息（或让位请求）立即唤醒进下一轮处理，
     * 等满仍无任何新消息 = 没人接话 → 编排层直接退出会话。执行侧钳 1~60（最多等 1 分钟）。
     * 兼容 camelCase 与**写进 content 条目里**的错位写法（模型偶发放错层级）。
     */
    val waitMessageSeconds: Int
        get() {
            val raw = params["wait_message"] ?: params["waitMessage"]
                ?: (params["content"] as? List<*>)?.firstNotNullOfOrNull { item ->
                    (item as? Map<*, *>)?.let { m -> m["wait_message"] ?: m["waitMessage"] }
                }
                ?: return 0
            return when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull() ?: 0
                else -> 0
            }
        }

    /** list_tools 的搜索关键词（null = 获取全部） */
    val toolQuery: String?
        get() = (params["query"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** tool 动作的目标工具名（parse 已校验非空） */
    val toolName: String? get() = (params["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** tool 动作的入参对象（无则空 Map） */
    @Suppress("UNCHECKED_CAST")
    val toolInput: Map<String, Any?> get() = params["input"] as? Map<String, Any?> ?: emptyMap()

    /** view_images 要查看的文件标签列表（params.refs；parse 已校验非空） */
    @Suppress("UNCHECKED_CAST")
    val viewRefs: List<String>
        get() = (params["refs"] as? List<Any?>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?: emptyList()

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(ConversationDecision::class.java)

        /** 解析失败兜底：RETURN，避免卡死在会话里 */
        val FALLBACK = ConversationDecision(
            action = ConversationAction.RETURN,
            reason = "解析失败兜底退出"
        )

        /**
         * 解析失败统一出口：记原文（截断）+ 返回兜底——实盘出现过
         * 「解析失败兜底退出」却无从知道 LLM 到底说了什么，诊断全靠猜
         */
        private fun fail(raw: String, why: String): ConversationDecision {
            // 原文记**首尾 + 总长度**：截断型失败（JSON 没写完、`}` 缺失）只有尾部
            // 能看出来，只记前 500 字恰恰看不到结尾——定位「为什么解析失败」全靠它
            val shown = if (raw.length <= 900) {
                raw
            } else {
                raw.take(600) + "\n……（共 ${raw.length} 字，以下是尾部）……\n" + raw.takeLast(300)
            }
            log.warn("决策解析失败（{}）——LLM 原文（{} 字）: {}", why, raw.length, shown)
            return FALLBACK
        }

        /** 连续 read_more 超限时的强制动作 */
        fun forceReturn(reason: String) = ConversationDecision(
            action = ConversationAction.RETURN,
            reason = reason
        )

        /**
         * 从 LLM 输出的统一信封解析
         */
        fun parse(llmOutput: String): ConversationDecision {
            // 模型偶发把输出包成「函数调用」标记 → 先翻译回纯 JSON 信封
            val normalized = LlmJson.toolCallEnvelope(llmOutput) ?: llmOutput
            val node = LlmJson.parseTree(normalized)
                ?: return fail(llmOutput, "非 JSON（或 markdown 包裹外无可提取的 JSON）")
            val actionText = node.get("action")?.asText()?.trim()?.lowercase()
                ?: return fail(llmOutput, "缺 action 字段")
            val action = when (actionText) {
                "read_more", "readmore" -> ConversationAction.READ_MORE
                "send" -> ConversationAction.SEND
                "stay" -> ConversationAction.STAY
                "return" -> ConversationAction.RETURN
                "list_tools", "tools" -> ConversationAction.LIST_TOOLS
                "tool", "call_tool" -> ConversationAction.CALL_TOOL
                "view_images", "view", "view_image" -> ConversationAction.VIEW_IMAGES
                else -> return fail(llmOutput, "未知 action=$actionText")
            }
            val params = LlmJson.paramsOf(node)
            // tool 调用必须带工具名，否则视为无效决策兜底退出（与 send 缺正文同规则）
            if (action == ConversationAction.CALL_TOOL &&
                (params["name"]?.toString()?.trim().isNullOrEmpty())
            ) {
                return fail(llmOutput, "tool 动作缺 name")
            }
            // view_images 必须带 refs（看图请求没对象 = 无效决策）
            if (action == ConversationAction.VIEW_IMAGES &&
                (params["refs"] as? List<*>)?.isNotEmpty() != true
            ) {
                return fail(llmOutput, "view_images 缺 refs")
            }
            val reason = node.get("reason")?.takeIf { !it.isNull }?.asText()
            val remember = node.get("remember")?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
            val todoAdd = node.get("todo_add")?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
            val todoDone = node.get("todo_done")?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
            // SEND 必须带正文，否则视为无效决策兜底退出
            if (action == ConversationAction.SEND && params["content"]?.toString().isNullOrBlank()) {
                return fail(llmOutput, "send 动作缺 content")
            }
            return ConversationDecision(
                action = action,
                params = params,
                reason = reason,
                remember = remember,
                todo_add = todoAdd,
                todo_done = todoDone
            )
        }
    }
}

/**
 * send 的一条待发消息（content 数组成员——**分段发送**，像真人分段打字）
 *
 * @param text 旧字符串写法的正文（结构化写法为 null）；结构化时用 [members]
 * @param replyToId 条级回复的目标消息 id（聊天记录行上的 #数字；一条消息最多一个）
 * @param delaySeconds 本条发送前的延迟（秒，模拟打字；编排层钳制并可被新消息唤醒）
 * @param members 结构化消息段（text / at / face，见 [OutgoingMember]）
 */
data class OutgoingMessage(
    val text: String? = null,
    val replyToId: Long? = null,
    val delaySeconds: Double = 0.0,
    val members: List<OutgoingMember> = emptyList()
) {
    /** 记账/提示用的可读摘要（text 优先；结构化则拼接段） */
    fun summarize(): String = text ?: buildString {
        replyToId?.let { append("回复 #$it：") }
        members.forEach { m ->
            when (m.type) {
                "text" -> append(m.text.orEmpty())
                "at" -> append("@${m.qq} ")
                "face" -> append("[表情:${m.id}]")
                else -> Unit
            }
        }
    }.trim()
}

/**
 * 聊天记录行（注入决策 prompt）
 */
data class ConversationChatLine(
    /** 发送者显示名；机器人为「我」 */
    val sender: String = "",
    val senderId: String = "",
    val isMe: Boolean = false,
    /** 展示用时间 */
    val time: String = "",
    val content: String = "",
    /**
     * OneBot message_id（渲染成行上的「（#数字）」标记）——LLM send 时
     * 以「回复 #id：」引用它。null = 老数据没有 id，不渲染。
     */
    val messageId: Long? = null
)

/**
 * 会话内决策请求
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ConversationRequest(
    @JsonProperty("persona")
    val persona: BotPersona = BotPersona(),

    @JsonProperty("mood")
    val mood: BotMood = BotMood(),

    @JsonProperty("timeHint")
    val timeHint: String = "",

    @JsonProperty("conversationType")
    val conversationType: String = "私聊",

    @JsonProperty("conversationName")
    val conversationName: String = "",

    /**
     * 当前会话身份（注入 system 的「当前会话」行）：
     * 群聊 = `群聊：群号 123456，群名「xxx」`；私聊 = `私聊：网友 QQ 123456，名字「xxx」`。
     * 由编排层按会话类型组装（群名进会话时查一次，网友名从已读历史取）。
     */
    @JsonProperty("conversationIdentity")
    val conversationIdentity: String = "",

    @JsonProperty("conversationId")
    val conversationId: String = "",

    /**
     * 机器人自己的 QQ 号（0 = 未知）——LLM 判断「这条消息是不是对我说的」的依据：
     * 聊天记录里 `@QQ` / `@名字(QQ)` 的号等于它才是艾特机器人。
     */
    @JsonProperty("selfQq")
    val selfQq: Long = 0L,

    @JsonProperty("shortTermMemory")
    val shortTermMemory: String = "",

    /** 待办清单摘要（多行 `- [ ] (id前4位) content`） */
    @JsonProperty("todos")
    val todos: String = "",

    /** 滚动操作日志（纯 string，多行） */
    @JsonProperty("actionLog")
    val actionLog: String = "",

    @JsonProperty("recentMessages")
    val recentMessages: List<ConversationChatLine> = emptyList(),

    /** 待插入新消息的系统注释行 */
    @JsonProperty("newMessageNotes")
    val newMessageNotes: List<String> = emptyList(),

    /** 主动发起的会话：必须先 send 破冰 */
    @JsonProperty("proactive")
    val proactive: Boolean = false,

    /** 本次会话已爬楼（read_more）的次数 */
    @JsonProperty("readMoreRounds")
    val readMoreRounds: Int = 0,

    /** 已覆盖最近多少条聊天记录（初始 N + 每次爬楼累加） */
    @JsonProperty("historyCovered")
    val historyCovered: Int = 0,

    /** 是否已翻到最早消息（再 read_more 也没有更早的历史） */
    @JsonProperty("reachedHistoryStart")
    val reachedHistoryStart: Boolean = false,

    /** 强制约束提示（如「有人发了新消息，优先回应」） */
    @JsonProperty("hint")
    val hint: String = "",

    /**
     * 工具上下文（编排层维护）：目录/搜索结果/执行结果的系统注释
     *
     * 由 ConversationController 在 list_tools / tool 动作后追加，
     * 注入下一轮 prompt 的「### 工具」段。
     */
    @JsonProperty("toolNotes")
    val toolNotes: List<String> = emptyList(),

    /**
     * 图片附件（**图片通道**）——编排层从聊天记录/新消息的 imageUrls
     * 下载加工（[org.dlucxoket.lucatbot.bot.llm.ImageResolver]），
     * 由 [org.dlucxoket.lucatbot.bot.llm.OpenAIAPI] 在边界组装进 content 数组。
     * 与 recentMessages/newMessageNotes 里的 `[图片 file=x]` 占位符对应。
     */
    @JsonProperty("images")
    val images: List<org.dlucxoket.lucatbot.bot.llm.PromptImage> = emptyList(),

    /**
     * **未能附上**的图片文件标签（下载失败/过期/旧数据无地址）——
     * 编排层把事实如实告诉 LLM：清单里的图它**确实看不到**，
     * 杜绝「附件没来却假装看过图」的幻觉（实盘出过：一张图没收到却
     * 声称「盯着图看了半天」）。
     */
    @JsonProperty("missingImages")
    val missingImages: List<String> = emptyList()
)

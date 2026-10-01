package org.dlucxoket.lucatbot.bot.agent.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.dlucxoket.lucatbot.bot.persona.BotPersona

/**
 * 会话选择类型
 */
enum class SessionPickType {
    /** 被动：打开某个私聊 */
    PRIVATE,

    /** 被动：打开某个群聊 */
    GROUP,

    /** 主动：去私聊某人（进入后首条由机器人先说） */
    PROACTIVE
}

/**
 * 选中的会话（none / idle 时用 null 表达，不出现 "none" 字符串）
 *
 * @param conversationType 会话类型
 * @param targetId 对象 ID
 */
data class SessionPick(
    val conversationType: SessionPickType,
    val targetId: String
)

/**
 * 未读/候选会话摘要（注入选会话 prompt）
 */
data class SessionSummaryLine(
    /** "私聊" / "群聊" */
    val conversationType: String = "",
    val name: String = "",
    val id: String = "",
    /** 展示用时间 */
    val time: String = "",
    val unreadCount: Int = 0,
    val lastMessage: String = "",
    val isAtMe: Boolean = false,
    val isAtAll: Boolean = false,
    /**
     * 机器人上次在该会话发言距今（"刚刚"/"3分钟前"/…；null = 还没说过话）——
     * 选会话优先级依据：**刚发过言且还有未读**的会话优先（对话正热）。
     */
    val botSpokeAgo: String? = null,

    /**
     * 疑似被禁言的标记日期（MMdd，跨年 yyyy-MM-dd；null = 无记录）——
     * 发送失败 retCode 判禁言时打点、发送成功清除（见 LucatBot.markSuspectedBan）
     */
    val suspectedBanDate: String? = null,

    /**
     * 该会话主体的**记忆摘要**（最新几条拼成单行；null = 无记忆）——
     * 挂在行尾让选会话 LLM 直接看到「关于这个人/群我记得什么」，
     * 待办/记忆驱动才能与具体会话对上（泛概览对不上行是实盘缺口）
     */
    val memoryBrief: String? = null
)

/**
 * 可主动找的人（仅空闲唤醒时出现）
 */
data class ProactiveCandidate(
    val name: String = "",
    val id: String = "",
    /** 亲密度/近况提示，如「上次聊到 xxx / 亲密度高 / 这个点他可能在」 */
    val hint: String = ""
)

/**
 * 会话选择请求（JSON 只在 LLM 边界；这里全程 OOP）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionPickerRequest(
    @JsonProperty("persona")
    val persona: BotPersona = BotPersona(),

    @JsonProperty("mood")
    val mood: BotMood = BotMood(),

    @JsonProperty("timeHint")
    val timeHint: String = "",

    @JsonProperty("shortTermMemory")
    val shortTermMemory: String = "",

    /** 待办清单摘要（多行 `- [ ] (id前4位) content`） */
    @JsonProperty("todos")
    val todos: String = "",

    /** 滚动操作日志（纯 string，多行） */
    @JsonProperty("actionLog")
    val actionLog: String = "",

    @JsonProperty("sessions")
    val sessions: List<SessionSummaryLine> = emptyList(),

    /**
     * 冷却中的会话（刚退出还没满 2 分钟）——每条如
     * `group:123（45 秒内别再进…）`，注入「### 刚退出（冷却中）」段，
     * 由 prompt 约束**别马上再进**（替代代码硬过滤，交模型判断）
     */
    @JsonProperty("cooldownNotes")
    val cooldownNotes: List<String> = emptyList(),

    /** 可主动找的人；空列表表示不展示该区块 */
    @JsonProperty("proactiveCandidates")
    val proactiveCandidates: List<ProactiveCandidate> = emptyList(),

    /**
     * 工具上下文（编排层维护）：目录/搜索结果/执行结果的系统注释
     *
     * 由 MessageListController 在 list_tools / tool 动作后追加，
     * 注入下一轮 prompt 的「### 工具」段。
     */
    @JsonProperty("toolNotes")
    val toolNotes: List<String> = emptyList(),

    /** 可见工具名固定清单（常驻 prompt：不用先 list_tools 就知道有啥工具） */
    @JsonProperty("toolNames")
    val toolNames: List<String> = emptyList()
)

/**
 * 会话选择决策动作
 *
 * @param wire LLM 线上协议里的规范动作名——**词表单一源**（同 [ConversationAction]）：
 *   prompt 动作行、`SessionDecisionParser.parse`、标签形态翻译都从伴生取
 */
enum class SessionAction(val wire: String) {
    /** 打开一个会话（pick 非空） */
    OPEN_CONVERSATION("open_conversation"),

    /** 不看任何会话，去空闲 */
    IDLE("idle"),

    /** 查看/搜索工具目录（全量或按关键词） */
    LIST_TOOLS("list_tools"),

    /** 调用一个工具（toolName + toolInput） */
    CALL_TOOL("tool");

    companion object {
        /** 别名 → 规范动作（模型偶发写法；与 wire 一起构成完整词表） */
        val ALIASES: Map<String, SessionAction> = mapOf(
            "tools" to LIST_TOOLS,
            "call_tool" to CALL_TOOL
        )

        /** 完整可识别词表（规范名 + 别名）——标签形态翻译的判定依据（计算属性，同 ConversationAction） */
        val WIRE_NAMES: Set<String>
            get() = SessionAction.entries.mapTo(mutableSetOf<String>()) { it.wire } + ALIASES.keys

        /** 线上动作名 → 枚举（调用方已 lowercase）；未收录 → null（走兜底空闲） */
        fun fromWire(name: String): SessionAction? =
            SessionAction.entries.firstOrNull { it.wire == name } ?: ALIASES[name]

        /**
         * prompt 动作行文本——**渲染结果必须与历史字面量逐字一致**（测试断言依赖）。
         * 参数同 `ConversationAction.promptList`
         */
        fun promptList(separator: String = " | ", quoted: Boolean = true): String =
            SessionAction.entries.joinToString(separator) {
                if (quoted) "\"${it.wire}\"" else it.wire
            }
    }
}

/**
 * 会话选择决策（统一信封的 OOP 形态；替代「SessionPick/null」二值输出，
 * 让消息列表智能体也能查工具目录、调用工具）
 *
 * @param action 决策动作
 * @param pick OPEN_CONVERSATION 时非空
 * @param query LIST_TOOLS 的搜索关键词（null = 全部）
 * @param toolName CALL_TOOL 的目标工具名
 * @param toolInput CALL_TOOL 的入参对象
 * @param todoAdd 新增待办内容（可选，与 action 无关的副作用字段，任何决策都可携带）
 * @param todoDone 完成待办的 id 或内容片段（可选，同上）
 * @param reason 内心动机（不发出）
 * @param idleSeconds IDLE 的空闲休眠秒数（`params.seconds`；0 = LLM 没给 →
 *   编排层按心情随机兜底）——空闲多久由 LLM 判断，不再纯代码随机
 */
data class SessionDecision(
    val action: SessionAction,
    val pick: SessionPick? = null,
    val query: String? = null,
    val toolName: String? = null,
    val toolInput: Map<String, Any?> = emptyMap(),
    val todoAdd: String? = null,
    val todoDone: String? = null,
    val reason: String? = null,
    val idleSeconds: Int = 0
)

/**
 * 从统一信封解析 [SessionDecision]
 *
 * 语义与旧 [SessionPickParser] 兼容：`open_conversation`（参数有效）→ 打开会话；
 * `idle`、参数无效、**无法解析的垃圾输出** → 空闲兜底，绝不卡死主循环。
 *
 * `todo_add` / `todo_done` 是与 action 无关的顶层副作用字段——
 * 任何动作（含兜底 idle）都携带，由编排层在执行 action 前落地。
 */
object SessionDecisionParser {
    private val log = org.slf4j.LoggerFactory.getLogger(SessionDecisionParser::class.java)

    /**
     * 兜底空闲并记 LLM 原文（截断）——解析失败时能看到模型到底说了什么，
     * 不用对着「兜底空闲」猜
     */
    private fun idleLogged(raw: String, why: String, todoAdd: String? = null, todoDone: String? = null): SessionDecision {
        log.warn("选会话决策解析失败（{}）——LLM 原文: {}", why, raw.take(500))
        return idle(why, todoAdd, todoDone)
    }

    fun parse(llmOutput: String): SessionDecision {
        // 模型偶发把输出包成「函数调用」标记 → 先翻译回纯 JSON 信封（同会话内决策；
        // 「已知动作」由 SessionAction 词表裁决，不认识的名字才当工具调用）
        val normalized = LlmJson.toolCallEnvelope(llmOutput, SessionAction.WIRE_NAMES) ?: llmOutput
        val node = LlmJson.parseTree(normalized)
            ?: return idleLogged(llmOutput, "非 JSON 或无可提取 JSON，兜底空闲")
        val actionText = node.get("action")?.asText()?.trim()?.lowercase()
            ?: return idleLogged(llmOutput, "缺 action 字段，兜底空闲")
        val reason = node.get("reason")?.takeIf { !it.isNull }?.asText()
        val todoAdd = node.get("todo_add")?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
        val todoDone = node.get("todo_done")?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }

        return when (SessionAction.fromWire(actionText)) {
            SessionAction.OPEN_CONVERSATION -> {
                val pick = SessionPickParser.parse(normalized)
                    ?: return idleLogged(llmOutput, "open_conversation 参数无效，兜底空闲", todoAdd, todoDone)
                SessionDecision(
                    SessionAction.OPEN_CONVERSATION, pick = pick,
                    todoAdd = todoAdd, todoDone = todoDone, reason = reason
                )
            }

            SessionAction.IDLE -> SessionDecision(
                SessionAction.IDLE,
                idleSeconds = idleSecondsOf(LlmJson.paramsOf(node)),
                todoAdd = todoAdd, todoDone = todoDone, reason = reason
            )

            SessionAction.LIST_TOOLS -> {
                val params = LlmJson.paramsOf(node)
                val query = params["query"]?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                SessionDecision(
                    SessionAction.LIST_TOOLS, query = query,
                    todoAdd = todoAdd, todoDone = todoDone, reason = reason
                )
            }

            SessionAction.CALL_TOOL -> {
                val params = LlmJson.paramsOf(node)
                val name = params["name"]?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                    ?: return idleLogged(llmOutput, "工具名缺失，兜底空闲", todoAdd, todoDone)
                val input = LlmJson.toolInputOf(params)
                SessionDecision(
                    SessionAction.CALL_TOOL, toolName = name, toolInput = input,
                    todoAdd = todoAdd, todoDone = todoDone, reason = reason
                )
            }

            // when 已覆盖全部枚举值——此处仅为 Kotlin 穷尽性收口（fromWire 未收录的名字）
            null -> idleLogged(llmOutput, "未知 action=$actionText，兜底空闲", todoAdd, todoDone)
        }
    }

    /** 兜底空闲（副作用字段照样保留——LLM 已经说要记的待办不能因 action 无效丢掉） */
    private fun idle(reason: String, todoAdd: String? = null, todoDone: String? = null) =
        SessionDecision(SessionAction.IDLE, todoAdd = todoAdd, todoDone = todoDone, reason = reason)

    /**
     * idle 的空闲秒数（`params.seconds`）——LLM 决定睡多久
     *
     * 数字/数字字符串都收；缺省、坏值、非正数 → 0（编排层按心情随机兜底）
     */
    private fun idleSecondsOf(params: Map<String, Any?>): Int {
        val raw = when (val s = params["seconds"]) {
            is Number -> s.toInt()
            is String -> s.trim().toIntOrNull() ?: 0
            else -> 0
        }
        return raw.coerceAtLeast(0)
    }
}

/**
 * 从统一信封解析 SessionPick（保留：open_conversation 的底层提取，
 * [SessionDecisionParser] 复用；也作为旧测试的稳定入口）
 */
object SessionPickParser {
    fun parse(llmOutput: String): SessionPick? {
        val node = LlmJson.parseTree(llmOutput) ?: return null
        val action = node.get("action")?.asText()?.trim()?.lowercase() ?: return null
        // 词表裁决（别名也认）——不再硬编码 wire 字面量
        if (SessionAction.fromWire(action) != SessionAction.OPEN_CONVERSATION) return null

        val params = LlmJson.paramsOf(node)
        val typeText = params["type"]?.toString()?.trim()?.lowercase() ?: return null
        val target = params["target"]?.toString()?.trim().orEmpty()
        if (target.isEmpty()) return null

        val type = when (typeText) {
            "private" -> SessionPickType.PRIVATE
            "group" -> SessionPickType.GROUP
            "proactive" -> SessionPickType.PROACTIVE
            else -> return null
        }
        return SessionPick(conversationType = type, targetId = target)
    }
}

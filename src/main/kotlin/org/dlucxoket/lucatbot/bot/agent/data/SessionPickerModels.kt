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
    val toolNotes: List<String> = emptyList()
)

/**
 * 会话选择决策动作
 */
enum class SessionAction {
    /** 打开一个会话（pick 非空） */
    OPEN_CONVERSATION,

    /** 不看任何会话，去空闲 */
    IDLE,

    /** 查看/搜索工具目录（全量或按关键词） */
    LIST_TOOLS,

    /** 调用一个工具（toolName + toolInput） */
    CALL_TOOL
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
 */
data class SessionDecision(
    val action: SessionAction,
    val pick: SessionPick? = null,
    val query: String? = null,
    val toolName: String? = null,
    val toolInput: Map<String, Any?> = emptyMap(),
    val todoAdd: String? = null,
    val todoDone: String? = null,
    val reason: String? = null
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
    fun parse(llmOutput: String): SessionDecision {
        val node = LlmJson.parseTree(llmOutput)
            ?: return idle("解析失败兜底空闲")
        val actionText = node.get("action")?.asText()?.trim()?.lowercase()
            ?: return idle("缺 action 字段，兜底空闲")
        val reason = node.get("reason")?.takeIf { !it.isNull }?.asText()
        val todoAdd = node.get("todo_add")?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
        val todoDone = node.get("todo_done")?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }

        return when (actionText) {
            "open_conversation" -> {
                val pick = SessionPickParser.parse(llmOutput)
                    ?: return idle("open_conversation 参数无效，兜底空闲", todoAdd, todoDone)
                SessionDecision(
                    SessionAction.OPEN_CONVERSATION, pick = pick,
                    todoAdd = todoAdd, todoDone = todoDone, reason = reason
                )
            }

            "idle" -> SessionDecision(SessionAction.IDLE, todoAdd = todoAdd, todoDone = todoDone, reason = reason)

            "list_tools", "tools" -> {
                val params = LlmJson.paramsOf(node)
                val query = params["query"]?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                SessionDecision(
                    SessionAction.LIST_TOOLS, query = query,
                    todoAdd = todoAdd, todoDone = todoDone, reason = reason
                )
            }

            "tool", "call_tool" -> {
                val params = LlmJson.paramsOf(node)
                val name = params["name"]?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                    ?: return idle("工具名缺失，兜底空闲", todoAdd, todoDone)
                @Suppress("UNCHECKED_CAST")
                val input = params["input"] as? Map<String, Any?> ?: emptyMap()
                SessionDecision(
                    SessionAction.CALL_TOOL, toolName = name, toolInput = input,
                    todoAdd = todoAdd, todoDone = todoDone, reason = reason
                )
            }

            else -> idle("未知 action=$actionText，兜底空闲", todoAdd, todoDone)
        }
    }

    /** 兜底空闲（副作用字段照样保留——LLM 已经说要记的待办不能因 action 无效丢掉） */
    private fun idle(reason: String, todoAdd: String? = null, todoDone: String? = null) =
        SessionDecision(SessionAction.IDLE, todoAdd = todoAdd, todoDone = todoDone, reason = reason)
}

/**
 * 从统一信封解析 SessionPick（保留：open_conversation 的底层提取，
 * [SessionDecisionParser] 复用；也作为旧测试的稳定入口）
 */
object SessionPickParser {
    fun parse(llmOutput: String): SessionPick? {
        val node = LlmJson.parseTree(llmOutput) ?: return null
        val action = node.get("action")?.asText()?.trim()?.lowercase() ?: return null
        if (action != "open_conversation") return null

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

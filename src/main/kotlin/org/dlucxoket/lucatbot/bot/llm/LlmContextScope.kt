package org.dlucxoket.lucatbot.bot.llm

/**
 * LLM 上下文组织模式（yaml `llm.context-mode`）
 *
 * - [COMPRESSED]（默认）：像现在这样，每一轮都把上下文**压进**「一条 system +
 *   一条 user」独立发出——轮与轮之间不共享 API 层上下文，全靠 prompt 里的
 *   操作日志/记忆/聊天记录窗口自证历史。省 token、每轮自包含。
 * - [NATIVE]：传统 agent 式**连贯 messages**——一次「巡逻」（消息列表 → 会话段）
 *   共用一套上下文（[SharedLlmContext]）：首轮 system 是所有 Agent 的静态指令
 *   合并，此后每轮的用户提示词进 user、AI 回复进 assistant、编排层的操作结果
 *   进 system，一直连贯到退出会话/进空闲为止。
 */
enum class LlmContextMode(val wire: String) {
    COMPRESSED("compressed"),
    NATIVE("native");

    companion object {
        /** 宽松解析：认不出/没配 → [COMPRESSED]（默认保持现行为） */
        fun parse(raw: String?): LlmContextMode =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: COMPRESSED
    }
}

/**
 * native 模式的**一套共享上下文**（一次巡逻一份）
 *
 * 线程模型：主循环的「选会话 + 会话段」在同一线程（会话执行锁内）跑，
 * 故按**线程隔离**挂载（[LlmContextScope] 用 ThreadLocal）——通知跳转、
 * 异步记忆整理等别的线程看不到这份上下文，自动退回 [LlmContextMode.COMPRESSED]。
 *
 * @param systemPrompt 首轮 system（所有 Agent 的静态指令合并 + 分隔空行）
 * @param knownInstructions 各 Agent 的静态指令段——每轮 ask 的 systemPrompt
 *   若以其中某段开头，说明该段已随首轮 system 发过，剥掉避免重复
 */
class SharedLlmContext(
    val systemPrompt: String,
    val knownInstructions: List<String>
) {
    private val lock = Any()
    private val _turns = mutableListOf<LlmTurn>()

    /** 当前上下文快照（只读拷贝——轨迹渲染/组包用） */
    val turns: List<LlmTurn>
        get() = synchronized(lock) { _turns.toList() }

    /** 一轮用户提示词（Agent 的动态状态 + 剧情数据）进 user */
    fun appendUser(text: String) {
        if (text.isBlank()) return
        synchronized(lock) { _turns += LlmTurn(LlmRole.USER, text) }
    }

    /** AI 的原始回应进 assistant（空回复不落笔——别让上下文里出现哑轮） */
    fun appendAssistant(text: String) {
        if (text.isBlank()) return
        synchronized(lock) { _turns += LlmTurn(LlmRole.ASSISTANT, text) }
    }

    /** 编排层的操作结果进 system（进入/退出会话、发送结果、工具已执行…） */
    fun appendSystem(text: String) {
        if (text.isBlank()) return
        synchronized(lock) { _turns += LlmTurn(LlmRole.SYSTEM, text) }
    }

    /**
     * 剥掉一次 ask 的 systemPrompt 里**已随首轮 system 发过的静态指令段**
     *
     * 压缩模式下 Agent 传的是「静态指令 + 动态状态」拼接；native 模式静态指令
     * 已在首轮 system，这里按 [knownInstructions] 做**精确前缀匹配**剥除
     * （两段由同一份代码产出，字节级一致，匹配是确定性的）。剥不掉就原样保留
     * ——宁可重复也不能丢状态（心情/时间/记忆都在动态段里）。
     */
    fun stripInstructions(systemPrompt: String?): String {
        val s = systemPrompt?.trim().orEmpty()
        if (s.isEmpty()) return ""
        knownInstructions.firstOrNull { s.startsWith(it) }?.let { return s.removePrefix(it).trimStart() }
        return s
    }
}

/**
 * 共享上下文的**挂载点**（线程隔离；仿 [TokenUsageScope] 的作用域式设计）
 *
 * 编排层（主循环）在一次巡逻前后 [begin] / [end]；[noteSystem] 供各编排点
 * 随手写操作结果——**没有上下文时是空操作**，调用方不必判断模式。
 */
object LlmContextScope {

    /** 当前模式（Spring 启动时由 LlmConfig 按 `llm.context-mode` 安装；默认压缩） */
    @Volatile
    var mode: LlmContextMode = LlmContextMode.COMPRESSED
        private set

    /** 安装模式（仅 LlmConfig / 测试调用） */
    fun installMode(newMode: LlmContextMode) {
        mode = newMode
    }

    private val current = ThreadLocal<SharedLlmContext?>()

    /** 是否 native 模式（模式是全局的，上下文本身按线程隔离） */
    val isNative: Boolean get() = mode == LlmContextMode.NATIVE

    /** native 模式且当前线程挂着上下文 → 返回它；否则 null（走压缩路径） */
    fun active(): SharedLlmContext? = if (mode == LlmContextMode.NATIVE) current.get() else null

    /** 当前线程是否处于共享上下文内 */
    val inContext: Boolean get() = current.get() != null

    /** 开一份新上下文（native 模式专用；同线程重复 begin 以最后一份为准） */
    fun begin(systemPrompt: String, knownInstructions: List<String>) {
        current.set(SharedLlmContext(systemPrompt.trim(), knownInstructions))
    }

    /** 收掉当前线程的上下文（异常路径也应收口，避免残留污染下一次巡逻） */
    fun end() {
        current.remove()
    }

    /** 编排层随手写一条「操作结果」系统轮；无上下文（压缩模式/别的线程）即空操作 */
    fun noteSystem(text: String) {
        active()?.appendSystem(text)
    }
}

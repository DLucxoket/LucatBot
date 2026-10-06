package org.dlucxoket.lucatbot.bot.agent

import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectRequest
import org.dlucxoket.lucatbot.bot.agent.data.MemoryReflectResult
import org.dlucxoket.lucatbot.bot.llm.LLM
import org.slf4j.LoggerFactory

/**
 * 记忆整理智能体
 *
 * 每次退出会话时被 [org.dlucxoket.lucatbot.bot.loop.ConversationController] 调用一次：
 * 复盘刚结束的对话，结合该对象的现有记忆（读取参考，去重、避免矛盾），
 * 判断有没有值得长期记住的信息（事实/看法/事件）；
 * 有则返回格式化的记忆 JSON，由控制器写入 [org.dlucxoket.lucatbot.bot.memory.MemoryService]。
 *
 * 解析失败或 LLM 异常一律兜底为空结果——记忆整理失败绝不影响退出流程。
 */
class MemoryAgent(
    llm: LLM = LLM.DEFAULT
) : Agent<MemoryReflectRequest, MemoryReflectResult>(llm) {
    private val log = LoggerFactory.getLogger(MemoryAgent::class.java)

    override val description: String = """
        退出会话时被调用的记忆整理智能体：
        复盘一段刚结束的对话，结合现有记忆判断有没有值得长期记住的信息
        （对人的事实、对人的看法/印象、重要事件/约定），
        有则输出格式化的记忆 JSON，由编排层写入长期记忆。
        普通闲聊不记；与现有记忆重复的不记。
    """.trimIndent()

    override val inputClass: Class<MemoryReflectRequest> = MemoryReflectRequest::class.java

    override fun handle(input: MemoryReflectRequest): MemoryReflectResult {
        return try {
            // 阶段2：写入前关键词复核（candidates 非空）——与阶段1（复盘）分支
            val llmOutput = if (input.candidates.isNotEmpty()) {
                if (input.matchedMemories.isEmpty()) {
                    // 查库零命中：没有可比对的，候选直接放行（省一次 LLM）
                    return MemoryReflectResult(input.candidates)
                }
                llm.ask(filterSystemPrompt(), filterUserContent(input))
            } else {
                llm.ask(buildSystemPrompt(), buildUserContent(input))
            }
            MemoryReflectResult.parse(llmOutput)
        } catch (e: Exception) {
            log.warn("记忆整理失败（忽略，不影响退出流程）: {}", e.message)
            MemoryReflectResult.DEGRADED
        }
    }

    /** 任务说明（复盘/去重防矛盾/输出格式契约/kind 说明/关键词要求）→ system 消息 */
    private fun buildSystemPrompt(): String {
        return """
            你正在复盘一段刚结束的对话，决定要不要往长期记忆里记东西。
            【现有记忆】是读取参考，避免重复或矛盾。
            ## 输出（严格遵循）
            只输出纯JSON（不裹代码块），结构如下：
            {"memories": [{"kind": "fact|opinion|event", "content": "一句话", "tags": ["标签"], "keywords": ["关键词"]}], "done_todos": ["办完的待办引用"]}
            - kind：fact=客观事实（名字/喜好/重要情况等）、opinion=你对这个人的主观看法/印象、event=发生过的事/约定
            - content：一句话，简明扼要
            - tags：检索标签，可省略——keywords 会一并**落库**成为这条记忆的检索关键词，两者给一个就够
            - keywords：**尽量写全**（建议 5~10 个：content 里的核心名词/专名，外加同义词、别名、口语说法、相关词——如人名/外号/地名/爱好名/事件名）——**每条必给**：写入前会拿这些词逐个查该对象的已有记忆核对重复（覆盖面越广查库命中越准，命中会再复核一轮），写入成功后它们就成为该记忆的关键词
            - 没有要记的输出 {"memories": []}
            
            待办结算（user 消息里有【未完成待办】时才需要）：
            - 对照【本次会话内容】逐条判断哪些**确实已经办完**（去某群看了并回了话、查到了结果、当面回复汇报过）→ 原样照抄清单里的写法（id 片段或内容片段）放进 done_todos
            - 只是开了头、没办完、或拿不准 → 不要写（办完会标完成并留痕，写错会把没办的事记成办过）
            - 都没办完 → 省略 done_todos 或输出 "done_todos": []
            
            固定短记（user 消息里有【固定短记】时；**整体覆盖，不是追加**）：
            - 那是一小段**每轮决策都固定注入**的「当前要紧事小抄」（几十字：状态/关键约定/眼下最该记的事）
            - 需要更新 → 输出 "fixed_memory": "完整的新内容"——**旧内容整体被替换**，过时的自己删掉，别在后面追加
            - 不需要更新 → **省略该字段**（旧内容原样保留）；没什么值得常驻的就不写
            - 写要短、准、当下有效（写长了每轮都烧 token）

            只记值得长期保留的：
            - 对人的事实（名字、喜好、重要情况）、对人的看法/印象、重要事件/约定
            - 普通闲聊、天气式对话不要记
            - 与现有记忆重复的不要记；与现有记忆矛盾的用新信息覆盖式表述（按最新信息记）

            身份判定（重要）：
            - 同一个 QQ 号就是同一个人，QQ 号是唯一身份依据
            - 聊天记录里的名字带 QQ 号；名字可能标注「（QQ昵称 xx）」——展示名是群名片、括号里是 QQ 昵称，两者同属一人
            - 网友随时可能改群名片或改 QQ 名：先后名字不同、改名，都不算换了人，也永远不要产出「先后使用两个昵称」「实为同一人」这类记忆
            - 名字本身完全可以记（这是典型的 fact）：群名片叫什么、QQ 昵称是什么、以前叫什么原名 xx，都照记不误——按最新展示名记，旧名不同就在同一条里说明「原名 xx」，不要分成两条记忆

            重复与时间（重要）：
            - 聊天记录行尾带「（已记忆）」的，表示这条消息**已经记过了**——不要再为它产出任何记忆，换个说法复读也算重复
            - 【现有记忆】每行尾部的（记于MM-DD）是写入日期——那是过去发生的事，不是刚发生的；跨天的旧事不要当成当前剧情去接话
        """.trimIndent()
    }

    /** 剧情数据（现有记忆 + 本次会话内容 + 未完成待办 + 时间）→ user 消息；纯数据 */
    private fun buildUserContent(input: MemoryReflectRequest): String {
        val existing = input.existingMemory.ifBlank { "（暂无）" }
        val content = input.conversationContent.ifBlank { "（无）" }
        val todosText = if (input.pendingTodos.isBlank()) {
            ""
        } else {
            "\n【未完成待办】\n${input.pendingTodos}"
        }
        val fixedText = "\n【固定短记（当前内容，要改就整体覆盖）】\n${input.fixedMemory.ifBlank { "（暂无）" }}"
        val timeLine = if (input.timeHint.isNotBlank()) {
            "\n当前时间（北京时间）：${input.timeHint}"
        } else {
            ""
        }
        return """
            【现有记忆】
            $existing
            【本次会话内容】
            $content$fixedText$todosText$timeLine
        """.trimIndent().fixIndent("            ")
    }

    /** 阶段2（写入前关键词复核）任务说明 → system 消息 */
    private fun filterSystemPrompt(): String {
        return """
            你在做记忆写入前的最后复核，防止同一件事被换个说法记两遍。
            【候选记忆】是阶段1复盘产出、准备写入的新记忆；
            【关键词命中的已有记忆】是用每条候选的关键词在该对象已有记忆里查到的命中结果。
            逐条判断每条候选是否与命中结果**重复**：
            - 同一事实/事件换个说法、同义改写、措辞不同但核心信息相同 → 重复，删掉
            - 只是沾边相关、说的是另一件事 → 保留
            - 与命中结果矛盾（同一件事信息更新了）→ 保留候选（按最新信息覆盖）
            ## 输出（严格遵循）
            只输出纯JSON（不裹代码块），结构如下：
            {"memories": [{"kind": "fact|opinion|event", "content": "一句话", "tags": ["标签"], "keywords": ["关键词"]}]}
            - 只输出**保留**下来的候选，kind/content/tags/keywords 原样照抄，不要改写
            - 全部重复就输出 {"memories": []}
        """.trimIndent()
    }

    /** 阶段2 剧情数据（候选 + 关键词命中的已有记忆）→ user 消息；纯数据 */
    private fun filterUserContent(input: MemoryReflectRequest): String {
        val candidates = input.candidates.joinToString("\n") { item ->
            val kind = item.kind.ifBlank { "?" }
            val keywords = item.keywords.takeIf { it.isNotEmpty() }
                ?.joinToString("、")?.let { "（关键词：$it）" } ?: ""
            "- [$kind] ${item.content}$keywords"
        }
        val hits = input.matchedMemories.joinToString("\n") { "- $it" }
        return """
            【候选记忆】
            $candidates
            【关键词命中的已有记忆】
            $hits
        """.trimIndent().fixIndent("            ")
    }
}

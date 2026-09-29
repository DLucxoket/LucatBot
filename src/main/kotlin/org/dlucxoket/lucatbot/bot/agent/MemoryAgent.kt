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
            val systemPrompt = buildSystemPrompt()
            val userContent = buildUserContent(input)
            val llmOutput = llm.ask(systemPrompt, userContent)
            MemoryReflectResult.parse(llmOutput)
        } catch (e: Exception) {
            log.warn("记忆整理失败（忽略，不影响退出流程）: {}", e.message)
            MemoryReflectResult.EMPTY
        }
    }

    /** 任务说明（复盘/去重防矛盾/输出格式契约/kind 说明）→ system 消息 */
    private fun buildSystemPrompt(): String {
        return """
            你正在复盘一段刚结束的对话，决定要不要往长期记忆里记东西。
            【现有记忆】是读取参考，避免重复或矛盾。
            ## 输出（严格遵循）
            只输出纯JSON（不裹代码块），结构如下：
            {"memories": [{"kind": "fact|opinion|event", "content": "一句话", "tags": ["标签"]}]}
            - kind：fact=客观事实（名字/喜好/重要情况等）、opinion=你对这个人的主观看法/印象、event=发生过的事/约定
            - content：一句话，简明扼要
            - tags：检索标签，可选，没有就省略
            - 没有要记的输出 {"memories": []}
            
            只记值得长期保留的：
            - 对人的事实（名字、喜好、重要情况）、对人的看法/印象、重要事件/约定
            - 普通闲聊、天气式对话不要记
            - 与现有记忆重复的不要记；与现有记忆矛盾的用新信息覆盖式表述（按最新信息记）

            身份判定（重要）：
            - 同一个 QQ 号就是同一个人，QQ 号是唯一身份依据
            - 聊天记录里的名字带 QQ 号；名字可能标注「（QQ昵称 xx）」——展示名是群名片、括号里是 QQ 昵称，两者同属一人
            - 网友随时可能改群名片或改 QQ 名：先后名字不同、改名，都不算换了人，也永远不要产出「先后使用两个昵称」「实为同一人」这类记忆
            - 名字本身完全可以记（这是典型的 fact）：群名片叫什么、QQ 昵称是什么、以前叫什么原名 xx，都照记不误——按最新展示名记，旧名不同就在同一条里说明「原名 xx」，不要分成两条记忆
        """.trimIndent()
    }

    /** 剧情数据（现有记忆 + 本次会话内容 + 时间）→ user 消息；纯数据 */
    private fun buildUserContent(input: MemoryReflectRequest): String {
        val existing = input.existingMemory.ifBlank { "（暂无）" }
        val content = input.conversationContent.ifBlank { "（无）" }
        val timeLine = if (input.timeHint.isNotBlank()) {
            "\n当前时间（北京时间）：${input.timeHint}"
        } else {
            ""
        }
        return """
            【现有记忆】
            $existing
            【本次会话内容】
            $content$timeLine
        """.trimIndent()
    }
}

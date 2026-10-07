package org.dlucxoket.lucatbot.bot.llm

/**
 * prompt 模板排版工具——**纯函数、无状态**
 *
 * 从 `org.dlucxoket.lucatbot.bot.agent.Agent` 下沉出来：基类的职责是「LLM 驱动的
 * 处理单元」（JSON↔OOP 边界 + 模板方法），不该再兼职字符串模板排版（SRP）。
 *
 * **解决的问题**：raw string 模板里插了多行值（人格 / 记忆 / 待办 / 操作日志 /
 * 聊天记录…）时，插值续行是 0 缩进，把 `trimIndent()` 的 minIndent 拉到 0 →
 * 整段 prompt 保留源码缩进（实测会话决策 prompt **105/123 行**各带 12 空格，
 * 每轮白烧 token）。
 *
 * 判定：**首个非空行仍带 [PROMPT_TEMPLATE_INDENT]** 才说明 trimIndent 没生效，
 * 此时逐行剥掉最多一个缩进（模板行有、插值续行没有，正好对齐）；已生效（首行
 * 0 缩进）则原样返回——**幂等**，两步调用也安全。
 */

/** 各 Agent 的 raw string 模板源码缩进（统一 12 空格，改了这里就全局生效） */
const val PROMPT_TEMPLATE_INDENT = "            "

/**
 * 修正被多行插值打失效的 `trimIndent()` 左 margin（**省 token**）
 *
 * 用法：`return """…模板…""".trimIndent().fixIndent()`
 *
 * @param indent 模板源码缩进；默认 [PROMPT_TEMPLATE_INDENT]
 */
fun String.fixIndent(indent: String = PROMPT_TEMPLATE_INDENT): String {
    val lines = lines()
    val first = lines.firstOrNull { it.isNotBlank() } ?: return this
    if (!first.startsWith(indent)) return this
    return lines.joinToString("\n") { if (it.startsWith(indent)) it.substring(indent.length) else it }
}

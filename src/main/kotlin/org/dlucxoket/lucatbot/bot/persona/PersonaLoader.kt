package org.dlucxoket.lucatbot.bot.persona

import org.slf4j.LoggerFactory
import java.io.File

/**
 * 人格文件加载器
 *
 * **只读原文、不解析**：`persona.md` 全文放进 [BotPersona.rawContent]，
 * prompt 由 [BotPersona.toPrompt] 原样拼接——文件里写了什么模型就看到什么。
 * 此前的逐行字段解析（名字/性格/社交倾向…）已删除：解析了没有消费方。
 *
 * 读取顺序：
 * 1. 工作目录下的 `persona.md`（可随时编辑，重启生效）
 * 2. classpath 内置副本 `/persona.md`（jar 自带兜底）
 * 3. 都没有 → 空人格（prompt 的人格段留空，启动日志告警）
 */
object PersonaLoader {

    private val log = LoggerFactory.getLogger(PersonaLoader::class.java)

    private const val EXTERNAL_PATH = "persona.md"
    private const val CLASSPATH_RESOURCE = "/persona.md"

    /** 加载结果缓存（启动时读一次） */
    val persona: BotPersona by lazy { load() }

    /** 读取人格文件**原文**（不解析任何字段） */
    fun load(): BotPersona {
        val external = File(EXTERNAL_PATH)
        val text = when {
            external.exists() -> external.readText(Charsets.UTF_8)
            else -> runCatching {
                PersonaLoader::class.java.getResourceAsStream(CLASSPATH_RESOURCE)?.readBytes()?.toString(Charsets.UTF_8)
            }.getOrNull()
        }

        if (text == null) {
            log.warn("persona.md 不存在，人格段为空（prompt 不注入人格）")
            return BotPersona()
        }

        val source = if (external.exists()) EXTERNAL_PATH else "内置副本"
        log.info("已加载 $source 人格原文（{} 字，不解析直接拼进 prompt）", text.length)
        return BotPersona(rawContent = text)
    }
}

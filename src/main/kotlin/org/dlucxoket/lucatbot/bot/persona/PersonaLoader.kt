package org.dlucxoket.lucatbot.bot.persona

import org.slf4j.LoggerFactory
import java.io.File

/**
 * 人格文件加载器
 *
 * 从 `persona.md` 读取机器人名字、性格等信息（运行时读取，不再散落在代码里）。
 *
 * 读取顺序：
 * 1. 工作目录下的 `persona.md`（可随时编辑，重启生效）
 * 2. classpath 内置副本 `/persona.md`（jar 自带兜底）
 * 3. 代码内置默认值（[BotPersona] 构造默认值，文件缺字段时逐字段回退）
 *
 * 格式：`- 字段名：值`，已知字段解析为结构化字段（名字/性格/说话风格/
 * 兴趣/社交倾向/回复积极性）；**整个文件原文注入 prompt**（rawContent），
 * 未知字段（如「主人」）不会丢失。
 */
object PersonaLoader {

    private val log = LoggerFactory.getLogger(PersonaLoader::class.java)

    private const val EXTERNAL_PATH = "persona.md"
    private const val CLASSPATH_RESOURCE = "/persona.md"

    /** 加载结果缓存（启动时读一次） */
    val persona: BotPersona by lazy { load() }

    /**
     * 读取人格文件并解析
     */
    fun load(): BotPersona {
        val external = File(EXTERNAL_PATH)
        val text = when {
            external.exists() -> external.readText(Charsets.UTF_8)
            else -> runCatching {
                PersonaLoader::class.java.getResourceAsStream(CLASSPATH_RESOURCE)?.readBytes()?.toString(Charsets.UTF_8)
            }.getOrNull()
        }

        if (text == null) {
            log.warn("persona.md 不存在，使用代码内置默认人格")
            return BotPersona()
        }

        val source = if (File(EXTERNAL_PATH).exists()) EXTERNAL_PATH else "内置副本"
        return parse(text).also { log.info("已从 $source 加载人格：${it.name}") }
    }

    /**
     * 解析 persona.md 文本（供测试直接调用）
     *
     * **单一真相源定案**：prompt 一律以 [BotPersona.rawContent] 原文为准
     * （整个文件直接注入，所见即所得）；解析器**只提取「名字」**——它是
     * 业务标识（聊天记录里的「我」、会话内称呼）。文件里的其他字段
     * （性格/兴趣/社交倾向…）进原文即可生效，不再解析成结构化值——
     * 解析了也没有消费方（此前是隐性死代码）。
     *
     * 未出现「名字」时回退 [BotPersona] 构造默认值。
     */
    fun parse(text: String): BotPersona {
        var name = BotPersona().name

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("- ") && !line.startsWith("* ")) continue
            val body = line.substring(2).trim()
            // 兼容中文冒号与英文冒号
            val sepIdx = body.indexOf('：').takeIf { it >= 0 } ?: body.indexOf(':').takeIf { it >= 0 } ?: continue
            val key = body.substring(0, sepIdx).trim()
            val value = body.substring(sepIdx + 1).trim()
            if (value.isEmpty()) continue

            if (key == "名字") {
                name = value
            }
            // 其余字段：不解析，保留在 rawContent 原文里整体进 prompt
        }

        return BotPersona(name = name, rawContent = text)
    }
}

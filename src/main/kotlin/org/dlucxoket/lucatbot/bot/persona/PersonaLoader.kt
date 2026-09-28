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
 * 格式：`- 字段名：值`，字段名支持 名字/性格/说话风格/兴趣/社交倾向/回复积极性。
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
     * 未出现的字段回退到 [BotPersona] 构造默认值。
     */
    fun parse(text: String): BotPersona {
        val base = BotPersona()
        var name = base.name
        var personality = base.personality
        var speakingStyle = base.speakingStyle
        var interests = base.interests
        var socialness = base.socialness
        var responsiveness = base.responsiveness

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("- ") && !line.startsWith("* ")) continue
            val body = line.substring(2).trim()
            // 兼容中文冒号与英文冒号
            val sepIdx = body.indexOf('：').takeIf { it >= 0 } ?: body.indexOf(':').takeIf { it >= 0 } ?: continue
            val key = body.substring(0, sepIdx).trim()
            val value = body.substring(sepIdx + 1).trim()
            if (value.isEmpty()) continue

            when (key) {
                "名字" -> name = value
                "性格" -> personality = value
                "说话风格" -> speakingStyle = value
                "兴趣" -> interests = value.split('、', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                "社交倾向" -> value.toDoubleOrNull()?.let { socialness = it.coerceIn(0.0, 1.0) }
                "回复积极性" -> value.toDoubleOrNull()?.let { responsiveness = it.coerceIn(0.0, 1.0) }
                else -> log.debug("persona.md 未知字段，忽略: {}", key)
            }
        }

        return BotPersona(
            name = name,
            personality = personality,
            interests = interests,
            socialness = socialness,
            responsiveness = responsiveness,
            speakingStyle = speakingStyle
        )
    }
}

package org.dlucxoket.lucatbot.bot.llm

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 图片取材器——把聊天记录里的图片地址加工成 LLM 可用的 [PromptImage]（data URI）
 *
 * **单一职责**：只管「地址 → 字节 → data URI」+ 缓存，不关心消息语义
 * （谁发的、对应哪条记录由调用方处理）。
 *
 * - 来源：http(s) 下载（NapCat 的图片 url）或本地文件路径兜底
 * - **缓存**：同一地址只下载一次（有界 LRU，防反复拉同一张图烧带宽/内存）
 * - **体积闸**：超过 [maxBytes] 的图放弃（避免 data URI 撑爆请求体）
 * - **失败即 null**：网络不通/格式不明一律返回 null，调用方降级为
 *   「占位符文本、无附件」——图片通道绝不让会话链路失败
 */
class ImageResolver(
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val cacheCapacity: Int = 64
) {
    private val log = LoggerFactory.getLogger(ImageResolver::class.java)

    /** 有界 LRU 缓存：地址 → 加工好的 PromptImage */
    private val cache = object : LinkedHashMap<String, PromptImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PromptImage>): Boolean =
            size > cacheCapacity
    }

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * 解析图片地址为 [PromptImage]
     *
     * @param source 图片地址（http/https 或本地路径——入档时从消息段 url 收集）
     * @param ref    关联标识（占位符 file 标签，用于边界渲染 `[图片 <ref>]` 文本）
     * @return null = 拿不到（地址不可达/超限/格式不明），调用方跳过附件
     */
    @Synchronized
    fun resolve(source: String, ref: String): PromptImage? {
        val key = source.trim()
        if (key.isEmpty() || key.startsWith("base64")) return null

        cache[key]?.let { return it }

        val bytes = download(key) ?: return null
        if (bytes.isEmpty()) return null
        if (bytes.size > maxBytes) {
            log.debug("图片超限跳过（{} > {} 字节）: {}", bytes.size, maxBytes, ref)
            return null
        }
        val mime = sniffMime(bytes, key) ?: run {
            log.debug("无法识别图片格式，跳过附件: {}", ref)
            return null
        }
        val image = PromptImage(
            ref = ref.ifBlank { key.substringAfterLast('/').substringBefore('?') },
            dataUri = "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        )
        cache[key] = image
        return image
    }

    private fun download(source: String): ByteArray? = try {
        when {
            source.startsWith("http://") || source.startsWith("https://") -> downloadHttp(source)
            else -> {
                val file = File(source)
                if (file.isFile && file.length() <= maxBytes) file.readBytes() else null
            }
        }
    } catch (e: Exception) {
        log.debug("图片取材失败（跳过附件）source={}: {}", source, e.message)
        null
    }

    private fun downloadHttp(url: String): ByteArray? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() !in 200..299) {
            log.debug("图片下载失败 status={} url={}", resp.statusCode(), url)
            return null
        }
        // Content-Length 预检 + 实际字节复检（防大图撑爆内存）
        val declared = resp.headers().firstValueAsLong("content-length").orElse(-1L)
        if (declared > maxBytes) return null
        return resp.body()
    }

    /**
     * 图片格式嗅探（magic number 优先，扩展名兜底）
     *
     * @return MIME（如 `image/jpeg`）；null = 不认识
     */
    private fun sniffMime(bytes: ByteArray, source: String): String? {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "image/png"
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals("GIF8".toByteArray())) return "image/gif"
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        ) return "image/webp"

        val ext = source.substringAfterLast('/', "").substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> null
        }
    }
}

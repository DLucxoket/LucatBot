package org.dlucxoket.lucatbot.bot.llm

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 有界 LRU（accessOrder）——超容量淘汰最久未用 */
private class BoundedLru<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
}

/**
 * 图片取材器——把聊天记录里的图片地址加工成 LLM 可用的 [PromptImage]（data URI）
 *
 * **单一职责**：只管「地址 → 字节 → data URI」+ 缓存，不关心消息语义
 * （谁发的、对应哪条记录由调用方处理）。
 *
 * - 来源：http(s) 下载（NapCat 的图片 url）或本地文件路径兜底
 * - **成功缓存**：同一地址只下载一次（有界 LRU，防反复拉同一张图烧带宽/内存）
 * - **负缓存**：HTTP 4xx（确定性拒绝）记入失败表、同一地址不再重试——
 *   否则每轮 prompt 都会打一次必败请求刷日志；网络抖动/5xx **不**进失败表，
 *   下轮照常重试
 * - **请求头**：QQ 多媒体 CDN（multimedia.nt.qq.com.cn）缺 Referer/UA 直接 400，
 *   下载带浏览器 UA + `Referer: https://qq.com/`
 * - **体积闸**：超过 [maxBytes] 的图放弃（避免 data URI 撑爆请求体）
 * - **失败即 null**：任何拿不到的地址一律返回 null，调用方降级为
 *   「占位符文本、无附件」——图片通道绝不让会话链路失败
 *
 * @param fetchHttp HTTP 下载器（**依赖注入**：测试注入假下载器即可覆盖
 *   成功 / 4xx 负缓存 / 瞬时故障重试，无需打真实网络）
 */
class ImageResolver(
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val cacheCapacity: Int = 64,
    private val fetchHttp: (String) -> ByteArray = { httpGet(it) }
) {
    private val log = LoggerFactory.getLogger(ImageResolver::class.java)

    /** HTTP 非 2xx（区分确定性拒绝 4xx 与瞬时故障的依据） */
    class HttpStatusException(val code: Int, val body: String? = null) : Exception("HTTP $code")

    /** 成功缓存：地址 → 加工好的 PromptImage（有界 LRU） */
    private val cache = BoundedLru<String, PromptImage>(cacheCapacity)

    /**
     * 负缓存：确定性拒绝（4xx）的地址（有界 LRU，进程级、重启恢复重试）。
     * 只记 4xx——服务器拒了就是拒了，重试同样结果；
     * 超时/连接失败/5xx 属瞬时故障，不记、下轮照常重试。
     */
    private val rejected = BoundedLru<String, Boolean>(rejectedCapacity)

    /**
     * 解析图片地址为 [PromptImage]
     *
     * @param source 图片地址（http/https 或本地路径——入档时从消息段 url 收集）
     * @param ref    关联标识（占位符 file 标签，用于边界渲染 `[图片 <ref>]` 文本）
     * @param refresh **地址刷新器**（可空）：主地址 4xx（QQ 图片 rkey 短效过期，
     *   实测 `download url has expired`）时调用一次拿新鲜地址重试——
     *   调用方用 `get_msg(message_id)` 现取消息原文、按 file 标签取新 url。
     *   刷新只试一次；新地址同样被拒则一并进失败表，绝不循环。
     * @return null = 拿不到（地址不可达/超限/格式不明），调用方跳过附件
     */
    @Synchronized
    fun resolve(source: String, ref: String, refresh: (() -> String?)? = null): PromptImage? {
        val key = source.trim()
        if (key.isEmpty() || key.startsWith("base64")) return null
        if (rejected.containsKey(key)) return null  // 负缓存命中：静默跳过，不重试

        cache[key]?.let { return it }

        val bytes = fetchOrRefresh(key, ref, refresh) ?: return null
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
        log.debug("图片附件就绪: {}（{} 字节）", ref, bytes.size)
        return image
    }

    /**
     * 下载主地址；4xx 时经 [refresh] 刷新一次重试
     *
     * 失败分类：4xx = 确定性拒绝（进失败表；刷新也救不回就放弃）、
     * 其余（超时/5xx/异常）= 瞬时（不进失败表，下轮重试）。
     */
    private fun fetchOrRefresh(key: String, ref: String, refresh: (() -> String?)?): ByteArray? {
        try {
            return download(key)
        } catch (e: HttpStatusException) {
            if (e.code !in 400..499) {
                log.debug("图片下载失败（瞬时，下轮可重试）status={} ref={}", e.code, ref)
                return null
            }
            // 确定性拒绝：原地址进失败表（响应体带上——报错文本能指出 400 原因）
            rejected[key] = true
            log.debug(
                "图片地址确定性失败（{}），记入失败表: {} body: {}",
                e.code, ref, e.body?.take(300)
            )
            if (refresh == null) return null
            val fresh = runCatching { refresh() }.getOrNull()?.trim()
            if (fresh.isNullOrEmpty() || fresh == key) {
                log.debug("图片地址刷新无果（get_msg 未给出新鲜地址）: {}", ref)
                return null
            }
            if (rejected.containsKey(fresh)) return null
            try {
                val bytes = download(fresh).also { log.debug("图片地址刷新成功，重新下载: {}", ref) }
                rejected.remove(key)  // 刷新救回：撤销主地址负缓存（否则下轮短路在缓存查询之前）
                return bytes
            } catch (e2: HttpStatusException) {
                if (e2.code in 400..499) {
                    rejected[fresh] = true
                    log.debug("刷新后的地址仍被拒（{}）: {} body: {}", e2.code, ref, e2.body?.take(300))
                } else {
                    log.debug("刷新后下载失败（瞬时）status={} ref={}", e2.code, ref)
                }
                return null
            }
        } catch (e: Exception) {
            log.debug("图片取材失败（瞬时，下轮可重试）source={}: {}", key, e.message)
            return null
        }
    }

    private fun download(source: String): ByteArray = when {
        source.startsWith("http://") || source.startsWith("https://") -> fetchHttp(source)
        else -> {
            val file = File(source)
            if (file.isFile && file.length() <= maxBytes) file.readBytes()
            else throw HttpStatusException(404)  // 本地不存在 = 确定性失败
        }
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

    companion object {
        /** 负缓存容量（进程级；重启后恢复重试） */
        private const val rejectedCapacity = 256

        /** 共享 HTTP 客户端（连接复用；强制 HTTP/1.1——部分 CDN 对 h2 直接 400；[httpGet] 是默认 fetcher） */
        private val sharedHttp: HttpClient by lazy {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build()
        }

        /** 浏览器 UA——QQ 多媒体 CDN 对无 UA 的请求直接 400 */
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        /**
         * 真实 HTTP 下载（默认 fetcher）
         *
         * 请求形态对齐浏览器：UA + `Referer: https://qq.com/` + 浏览器 Accept +
         * 强制 HTTP/1.1（QQ CDN 对 h2 有 400 的前科）。非 2xx 抛
         * [HttpStatusException]（携带响应体前 300 字符供日志定位原因，4xx 由
         * 调用方进负缓存）。Content-Length 预检 + 调用方实际字节复检防大图。
         */
        fun httpGet(url: String): ByteArray {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Referer", "https://qq.com/")
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .GET()
                .build()
            val resp = sharedHttp.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() !in 200..299) {
                val body = resp.body()
                throw HttpStatusException(
                    resp.statusCode(),
                    String(body.copyOfRange(0, minOf(body.size, 300)), Charsets.UTF_8)
                )
            }
            val declared = resp.headers().firstValueAsLong("content-length").orElse(-1L)
            if (declared > 4L * 1024 * 1024) throw HttpStatusException(413)
            return resp.body()
        }
    }
}

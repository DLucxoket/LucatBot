package org.dlucxoket.lucatbot.bot.llm

import jakarta.annotation.PreDestroy
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.Message
import org.dlucxoket.lucatbot.model.message.member.ImageMessageMember
import org.dlucxoket.lucatbot.model.message.member.MemeMessageMember
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/**
 * 图片预取器——**Shiro 刚收到消息就异步下载缓存**
 *
 * 为什么必须在到达时刻下手：QQ 图片地址短效（实测 63 秒即
 * `download url has expired`），`get_msg` 刷新也只回同一条过期串，
 * NapCat/NTQQ 本地又没有落盘缓存——**地址唯一还活着的窗口就是消息
 * 到达后的头几秒**。入档的同时丢进下载池，等 prompt 组装时（通常 1~2 秒
 * 后的决策轮）直接命中 [ImageResolver] 的成功缓存。
 *
 * - 共享**同一个** [ImageResolver] 实例（LoopConfig 装配）——预取缓存与
 *   prompt 取材缓存必须是同一份，否则白下
 * - 双线程池（daemon）：下载不阻塞 Shiro 事件线程；[ImageResolver.resolve]
 *   自带 @Synchronized，预取进行中 prompt 线程会等它下完（同一地址只下一次）
 * - 失败不抛：地址过期由 ImageResolver 记负缓存，预取静默结束
 */
@Component
class ImagePrefetcher(private val resolver: ImageResolver) {
    private val log = LoggerFactory.getLogger(ImagePrefetcher::class.java)

    /** 下载池（daemon 线程，不阻止 JVM 退出） */
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "image-prefetch").apply { isDaemon = true }
    }

    /**
     * 收到消息即预取其中的图片/表情包（异步）
     *
     * 只管**刚到达的实时消息**——爬楼拉的旧地址早已过期，预取只会白打
     * 一次请求，交给 prompt 时的负缓存处理即可。
     */
    fun prefetch(message: Message?) {
        val chat = message as? ChatMessage ?: return
        targetsOf(chat).forEach { (url, ref) ->
            try {
                executor.execute { runCatching { resolver.resolve(url, ref) } }
            } catch (e: Exception) {
                log.debug("图片预取入队失败（跳过）: {}", e.message)
            }
        }
    }

    /** 同步预取（测试用：绕开异步竞态，直接跑完下载） */
    internal fun prefetchNow(message: Message?) {
        val chat = message as? ChatMessage ?: return
        targetsOf(chat).forEach { (url, ref) ->
            runCatching { resolver.resolve(url, ref) }
        }
    }

    /** 消息里的（地址, 文件标签）对——标签与 prompt 占位符的 file= 同源 */
    private fun targetsOf(chat: ChatMessage): List<Pair<String, String>> =
        chat.messageMembers.mapNotNull { m ->
            val raw = when (m) {
                is ImageMessageMember -> m.url
                is MemeMessageMember -> m.url
                else -> null
            }?.trim()
            if (raw.isNullOrEmpty() || raw.startsWith("base64")) return@mapNotNull null
            val file = when (m) {
                is ImageMessageMember -> m.fileName
                is MemeMessageMember -> m.fileName
                else -> null
            }
            val ref = file?.takeIf { it.isNotBlank() } ?: raw.substringAfterLast('/').substringBefore('?')
            raw to ref
        }.distinct()

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}

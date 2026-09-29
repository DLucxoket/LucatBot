package org.dlucxoket.lucatbot

import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.TextContent
import org.dlucxoket.lucatbot.bot.llm.ImageResolver
import org.dlucxoket.lucatbot.bot.llm.OpenAIAPI
import org.dlucxoket.lucatbot.bot.llm.PromptImage
import org.dlucxoket.lucatbot.model.message.AssetMarkers
import org.dlucxoket.lucatbot.model.message.ChatMessage
import org.dlucxoket.lucatbot.model.message.member.FaceMessageMember
import org.dlucxoket.lucatbot.model.message.member.ImageMessageMember
import org.dlucxoket.lucatbot.model.message.member.MemeMessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 图片通道（Shiro → model → Agent → LLM 边界）的纯函数链路
 *
 * 覆盖：占位符提取（AssetMarkers）、成员地址收集（ChatMessage.assetUrls）、
 * 地址 → data URI 加工（ImageResolver：本地读/缓存/体积闸/格式嗅探）、
 * LLM 边界的 content 数组组装（OpenAIAPI.userContents）。
 * Controller 的收集编排见 ConversationControllerTest.images gathered。
 */
class ImageChannelTest {

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(16) { 1 }

    // ------------------------------------------------------------------
    // 占位符提取（文本侧）
    // ------------------------------------------------------------------

    @Test
    fun `asset marker refs extract in order with file or url label`() {
        assertEquals(
            listOf("a.jpg", "b.png", ""),
            AssetMarkers.refs("看这个[图片 file=a.jpg]还有[表情包 url=b.png]以及[图片]")
        )
        assertEquals(emptyList<String>(), AssetMarkers.refs("纯文本没有图"))
        // describe 的 url= 形态（短 url 标签）
        assertEquals(listOf("c.png"), AssetMarkers.refs("[图片 url=c.png]"))
    }

    // ------------------------------------------------------------------
    // 成员地址收集（成员侧，与占位符同序）
    // ------------------------------------------------------------------

    @Test
    fun `asset urls collect image and meme members in order filtering base64`() {
        val msg = ChatMessage(listOf(
            TextMessageMember("前"),
            ImageMessageMember("https://x/a.png", "a.png"),
            FaceMessageMember(14),
            MemeMessageMember("https://x/b.webp", "b.webp"),
            ImageMessageMember("base64://xxxx", "c.png")  // base64 地址不收
        ))
        assertEquals(
            listOf("https://x/a.png", "https://x/b.webp"),
            msg.assetUrls(),
            "按成员顺序收集，base64/空白过滤"
        )
        assertEquals(emptyList<String>(), ChatMessage(listOf(TextMessageMember("纯文本"))).assetUrls())
    }

    // ------------------------------------------------------------------
    // ImageResolver（地址 → data URI）
    // ------------------------------------------------------------------

    @Test
    fun `resolver reads local file into png data uri and caches`() {
        val resolver = ImageResolver(maxBytes = 1024)
        val f = File.createTempFile("chan", ".tmp").apply { writeBytes(pngBytes) }

        val img = resolver.resolve(f.absolutePath, "selfie.png")
        assertNotNull(img, "本地文件应能解析")
        assertTrue(img!!.dataUri.startsWith("data:image/png;base64,"), "magic 嗅探为 png：${img.dataUri.take(40)}")
        assertEquals("selfie.png", img.ref)
        assertEquals(img, resolver.resolve(f.absolutePath, "selfie.png"), "同地址命中缓存")
    }

    @Test
    fun `resolver returns null for unresolvable sources`() {
        val resolver = ImageResolver(maxBytes = 1024)
        assertNull(resolver.resolve("", "x"), "空地址")
        assertNull(resolver.resolve("base64://AAAA", "x"), "base64 地址不走附件通道")
        assertNull(resolver.resolve(File("definitely-not-exist-xyz.png").absolutePath, "x"), "文件不存在")
    }

    @Test
    fun `resolver enforces size cap and rejects unknown format`() {
        // 体积闸：文件字节数超过 maxBytes → 放弃
        val small = ImageResolver(maxBytes = 8)
        val big = File.createTempFile("big", ".png").apply { writeBytes(pngBytes) }
        assertNull(small.resolve(big.absolutePath, "big.png"), "超限图跳过")

        // 格式嗅探：magic 不符且扩展名不认识 → 放弃
        val weird = File.createTempFile("weird", ".bin").apply { writeText("not an image at all") }
        assertNull(ImageResolver().resolve(weird.absolutePath, "weird.bin"), "未知格式跳过")
    }

    // ------------------------------------------------------------------
    // HTTP 路径（注入式 fetcher——不打真实网络）
    // ------------------------------------------------------------------

    @Test
    fun `http 4xx is negative cached and never retried`() {
        // 实盘教训：QQ CDN 必败地址每轮重试刷日志——4xx 必须只请求一次
        var calls = 0
        val resolver = ImageResolver(fetchHttp = { _ ->
            calls++
            throw ImageResolver.HttpStatusException(400)
        })
        assertNull(resolver.resolve("https://x/a.png", "a.png"))
        assertNull(resolver.resolve("https://x/a.png", "a.png"))
        assertEquals(1, calls, "4xx 确定性拒绝只请求一次（负缓存生效）")
    }

    @Test
    fun `transient http failure is retried next time`() {
        var calls = 0
        val resolver = ImageResolver(fetchHttp = { _ ->
            calls++
            throw RuntimeException("timeout")
        })
        assertNull(resolver.resolve("https://x/b.png", "b.png"))
        assertNull(resolver.resolve("https://x/b.png", "b.png"))
        assertEquals(2, calls, "瞬时故障不进负缓存，下轮照常重试")
    }

    @Test
    fun `http success resolves into data uri and caches`() {
        var calls = 0
        val resolver = ImageResolver(fetchHttp = { _ -> calls++; pngBytes })
        val img = resolver.resolve("https://x/c.png", "c.png")
        assertNotNull(img)
        assertTrue(img!!.dataUri.startsWith("data:image/png;base64,"), "magic 嗅探为 png")
        resolver.resolve("https://x/c.png", "c.png")
        assertEquals(1, calls, "成功结果缓存，同址只下载一次")
    }

    // ------------------------------------------------------------------
    // LLM 边界：content 数组组装
    // ------------------------------------------------------------------

    @Test
    fun `user contents interleave label text before each image`() {
        val contents = OpenAIAPI().userContents(
            "以下是聊天记录…",
            listOf(
                PromptImage("a.png", "data:image/png;base64,AAA"),
                PromptImage("b.jpg", "data:image/jpeg;base64,BBB")
            )
        )
        assertEquals(5, contents.size, "正文 + 每图两段（标签 + 图片）")
        assertTrue(contents[0] is TextContent)
        assertEquals("以下是聊天记录…", (contents[0] as TextContent).text())
        assertTrue(contents[1] is TextContent && (contents[1] as TextContent).text().contains("[图片 a.png]"),
            "图 1 前有与占位符同名的标签文本")
        assertTrue(contents[2] is ImageContent, "图 1 段是图片内容")
        assertTrue(contents[3] is TextContent && (contents[3] as TextContent).text().contains("[图片 b.jpg]"),
            "图 2 前有标签文本")
        assertTrue(contents[4] is ImageContent, "图 2 段是图片内容")
    }

    @Test
    fun `empty images yields single text content`() {
        val contents = OpenAIAPI().userContents("只有正文", emptyList())
        assertEquals(1, contents.size)
        assertTrue(contents[0] is TextContent)
    }
}

package org.dlucxoket.lucatbot.bot.tool

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 访问链接入参（url 必填 http/https；maxChars 截断上限） */
data class FetchUrlRequest(
    val url: String = "",
    val maxChars: Int = 2000
)

/** 访问链接结果（text = 页面正文文字；失败时 success=false + error） */
data class FetchUrlResult(
    val url: String = "",
    val text: String = "",
    val success: Boolean = true,
    val error: String? = null
)

/**
 * 访问链接工具——抓取网页正文（HTTP GET，HTML 去标签留文字）
 *
 * 模拟真人「点开链接看一眼」：聊天里出现链接/分享卡片时调用。
 * 只支持 http/https（防 file: 等本地协议读盘）；15s 超时、只收文本类
 * 内容（图片/压缩包等直接报错不下载）、正文截断防爆 prompt。
 *
 * JSON 边界：`{"url": …, "maxChars": …}` ↔ [FetchUrlRequest]
 */
object FetchUrlTool : Tool<FetchUrlRequest, FetchUrlResult>() {
    override val name: String = "FetchUrlTool"

    override val description: String =
        "功能：访问一个网页链接（http/https），抓取页面正文文字（HTML 自动去标签留文字），返回截断后的文本。\n" +
            "何时使用：聊天里出现链接/分享卡片，想知道里面写了什么时调用；图片、压缩包等非文本内容不适用。"

    override val parameterHint: String = "JSON: {\"url\": \"https://example.com/a\", \"maxChars\": 2000}"

    override val inputClass: Class<FetchUrlRequest> = FetchUrlRequest::class.java

    override fun handle(input: FetchUrlRequest): FetchUrlResult {
        val url = input.url.trim()
        if (url.isEmpty()) return FetchUrlResult(success = false, error = "缺少 url")
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return FetchUrlResult(url = url, success = false, error = "只支持 http/https 链接")
        }
        val maxChars = input.maxChars.coerceIn(200, 6_000)
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) LucatBot/1.0")
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json,text/*;q=0.9,*/*;q=0.1")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (response.statusCode() !in 200..399) {
                return FetchUrlResult(url = url, success = false, error = "HTTP ${response.statusCode()}")
            }
            val contentType = response.headers().firstValue("content-type").orElse("").lowercase()
            val textLike = contentType.isEmpty() || listOf("text", "html", "json", "xml", "javascript")
                .any { contentType.contains(it) }
            if (!textLike) {
                return FetchUrlResult(url = url, success = false, error = "不是文本内容（$contentType）")
            }
            val body = response.body().orEmpty()
            val raw = if (contentType.contains("html") || body.trimStart().startsWith("<")) {
                stripHtml(body)
            } else {
                body
            }
            val text = raw.replace(Regex("[ \\t]+"), " ").lines()
                .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (text.isBlank()) {
                FetchUrlResult(url = url, success = false, error = "页面没有可读文本")
            } else {
                FetchUrlResult(url = url, text = text.take(maxChars), success = true)
            }
        } catch (e: Exception) {
            FetchUrlResult(url = url, success = false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private val SCRIPT_STYLE = Regex("""<(script|style)[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")

    /**
     * HTML → 可读文字：去 script/style 与标签、还原常用实体、压空白
     * （页面正文提取的最小实现——要的是「大概写了什么」，不是排版还原）
     */
    internal fun stripHtml(html: String): String {
        var s = SCRIPT_STYLE.replace(html, " ")
        s = TAG.replace(s, " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&ldquo;", "“").replace("&rdquo;", "”")
        return s
    }
}

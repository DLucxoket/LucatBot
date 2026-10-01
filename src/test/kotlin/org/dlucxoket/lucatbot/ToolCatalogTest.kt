package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.tool.Tool
import org.dlucxoket.lucatbot.bot.tool.ToolCatalog
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 测试用工具：目录与调用网关的最小样本 */
private class CatalogFooTool : Tool<CatalogFooTool.Request, CatalogFooTool.Result>() {
    data class Request(val keyword: String = "")
    data class Result(val found: String = "", val success: Boolean = true)
    override val name = "CatalogFooTool"
    override val description = "功能：按关键词查 Foo 数据。\n何时使用：需要查 Foo 时。"
    override val parameterHint = "JSON: {\"keyword\": \"关键词\"}"
    override val inputClass: Class<Request> = Request::class.java
    override fun handle(input: Request) = Result(found = "foo:${input.keyword}")
}

private class CatalogBarTool : Tool<CatalogBarTool.Request, CatalogBarTool.Result>() {
    data class Request(val id: Long = 0L)
    data class Result(val ok: Boolean = true)
    override val name = "CatalogBarTool"
    override val description = "功能：查 Bar 详情。\n何时使用：需要 Bar 资料时。"
    override val parameterHint = "JSON: {\"id\": 1}"
    override val inputClass: Class<Request> = Request::class.java
    override fun handle(input: Request) = Result(ok = input.id > 0)
}

/** 抛异常的工具（验证 invoke 兜底不打断循环） */
private class BoomTool : Tool<BoomTool.Request, BoomTool.Result>() {
    data class Request(val x: String = "")
    data class Result(val ok: Boolean = false)
    override val name = "BoomTool"
    override val description = "功能：总是抛异常。\n何时使用：测试兜底。"
    override val parameterHint = "JSON: {}"
    override val inputClass: Class<Request> = Request::class.java
    override fun handle(input: Request): Result = throw IllegalStateException("boom")
}

/** 业务失败的工具（success=false + error，验证 invokeDetailed 的失败判定） */
private class FailTool : Tool<FailTool.Request, FailTool.Result>() {
    data class Request(val x: String = "")
    data class Result(val success: Boolean = false, val error: String? = null)
    override val name = "FailTool"
    override val description = "功能：总是返回业务失败。\n何时使用：测试结构化失败判定。"
    override val parameterHint = "JSON: {}"
    override val inputClass: Class<Request> = Request::class.java
    // error 造长：验证「截断不影响 isError」（判定发生在截断前的完整 JSON 上）
    override fun handle(input: Request) = Result(success = false, error = "业务失败：" + "A".repeat(500))
}

/** ToolCatalog：目录组装、关键词搜索、安全调用与截断 */
class ToolCatalogTest {

    private fun catalogWith(vararg tools: Tool<*, *>, maxResultChars: Int = 4_000) =
        ToolCatalog(toolsProvider = { tools.toList() }, maxResultChars = maxResultChars)

    @Test
    fun `catalog without query returns all entries with full intro`() {
        val text = catalogWith(CatalogFooTool(), CatalogBarTool()).catalog(null)

        assertTrue(text.contains("### CatalogFooTool"), text)
        assertTrue(text.contains("### CatalogBarTool"), text)
        // 介绍三要素：功能 / 何时使用 / 参数
        assertTrue(text.contains("功能：按关键词查 Foo 数据"), text)
        assertTrue(text.contains("何时使用：需要查 Foo 时"), text)
        assertTrue(text.contains("参数：{\"keyword\": \"关键词\"}"), "JSON: 前缀应剥掉：$text")
    }

    @Test
    fun `catalog search matches name and description case-insensitively`() {
        val catalog = catalogWith(CatalogFooTool(), CatalogBarTool())

        val byName = catalog.catalog("foo")
        assertTrue(byName.contains("CatalogFooTool"), byName)
        assertFalse(byName.contains("CatalogBarTool"), byName)

        val byDesc = catalog.catalog("Bar 资料")
        assertTrue(byDesc.contains("CatalogBarTool"), byDesc)
        assertEquals(1, catalog.count("Bar 资料"))
        assertEquals(2, catalog.count(null))
    }

    @Test
    fun `catalog with no match returns hint not blank`() {
        val text = catalogWith(CatalogFooTool()).catalog("不存在的东西")
        assertTrue(text.contains("没有匹配"), text)
        assertTrue(text.contains("list_tools"), text)
    }

    @Test
    fun `invoke executes tool through json boundary`() {
        val result = catalogWith(CatalogFooTool()).invoke(
            "CatalogFooTool",
            mapOf("keyword" to "abc")
        )
        assertTrue(result.contains("\"found\":\"foo:abc\""), result)
    }

    @Test
    fun `invoke is case insensitive on tool name`() {
        val result = catalogWith(CatalogFooTool()).invoke("catalogfootool", emptyMap())
        assertTrue(result.contains("foo:"), result)
    }

    @Test
    fun `invoke unknown tool returns error json`() {
        val result = catalogWith(CatalogFooTool()).invoke("Nope", emptyMap())
        assertTrue(result.contains("\"success\":false"), result)
        assertTrue(result.contains("不存在"), result)
        assertTrue(result.contains("list_tools"), "错误应提示先查目录：$result")
    }

    @Test
    fun `invoke exception is converted to error json not thrown`() {
        val result = catalogWith(BoomTool()).invoke("BoomTool", emptyMap())
        assertTrue(result.contains("\"success\":false"), result)
        assertTrue(result.contains("boom"), result)
    }

    @Test
    fun `long result is truncated`() {
        val long = "A".repeat(500)
        val tool = object : Tool<Map<String, Any?>, Map<String, Any?>>() {
            override val name = "LongTool"
            override val description = "功能：返回超长结果。\n何时使用：测试截断。"
            override val parameterHint = "JSON: {}"
            override val inputClass: Class<Map<String, Any?>> =
                @Suppress("UNCHECKED_CAST")
                Map::class.java as Class<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            override fun handle(input: Map<String, Any?>): Map<String, Any?> =
                mapOf("data" to long)
        }
        val result = ToolCatalog(toolsProvider = { listOf(tool) }, maxResultChars = 100)
            .invoke("LongTool", emptyMap())
        assertTrue(result.length < 200, "应被截断：len=${result.length}")
        assertTrue(result.contains("截断"), result)
    }

    @Test
    fun `orchestration-only tools are hidden from catalog and rejected on invoke`() {
        val catalog = ToolCatalog(
            toolsProvider = { listOf(CatalogFooTool(), CatalogBarTool()) },
            orchestrationOnly = setOf("CatalogFooTool")
        )

        // 目录与计数都不含编排专属工具
        assertFalse(catalog.catalog(null).contains("CatalogFooTool"), "编排专属工具不应进目录")
        assertEquals(1, catalog.count(null))
        assertFalse(catalog.catalog("foo").contains("### "), "搜索应只剩不可见工具的空结果")
        assertTrue(catalog.catalog("foo").contains("没有匹配"), catalog.catalog("foo"))

        // 调用被明确拒绝（大小写不敏感）
        val rejected = catalog.invoke("catalogfootool", mapOf("keyword" to "x"))
        assertTrue(rejected.contains("\"success\":false"), rejected)
        assertTrue(rejected.contains("编排层专属管理"), rejected)
    }

    // ---- invokeDetailed：结构化调用结果（编排层判失败不再嗅探字符串） ----

    @Test
    fun `invokeDetailed success result is not error`() {
        val r = catalogWith(CatalogFooTool()).invokeDetailed(
            "CatalogFooTool", mapOf("keyword" to "abc")
        )
        assertFalse(r.isError, "成功结果不应判为错误：${r.output}")
        assertTrue(r.output.contains("\"found\":\"foo:abc\""), r.output)
    }

    @Test
    fun `invokeDetailed flags success false as error`() {
        val r = catalogWith(FailTool()).invokeDetailed("FailTool", emptyMap())
        assertTrue(r.isError, "success=false 必须判错：${r.output}")
        assertTrue(r.output.contains("\"success\":false"), r.output)
    }

    @Test
    fun `invokeDetailed flags unknown tool as error`() {
        val r = catalogWith(CatalogFooTool()).invokeDetailed("Nope", emptyMap())
        assertTrue(r.isError, "工具不存在属编排层错误")
        assertTrue(r.output.contains("不存在"), r.output)
        assertTrue(r.output.contains("list_tools"), r.output)
    }

    @Test
    fun `invokeDetailed flags orchestration-only tool as error`() {
        val catalog = ToolCatalog(
            toolsProvider = { listOf(CatalogFooTool()) },
            orchestrationOnly = setOf("CatalogFooTool")
        )
        val r = catalog.invokeDetailed("catalogfootool", mapOf("keyword" to "x"))
        assertTrue(r.isError, "编排专属拒绝属编排层错误")
        assertTrue(r.output.contains("编排层专属管理"), r.output)
    }

    @Test
    fun `invokeDetailed flags thrown exception as error`() {
        val r = catalogWith(BoomTool()).invokeDetailed("BoomTool", emptyMap())
        assertTrue(r.isError, "执行异常必须判错")
        assertTrue(r.output.contains("\"success\":false"), r.output)
        assertTrue(r.output.contains("boom"), r.output)
    }

    @Test
    fun `invokeDetailed judges error before truncation`() {
        // 判失败必须用截断前的完整 JSON：FailTool 输出 success=false + 500+ 字符 error，
        // 截断后 JSON 已不完整——若先截断再解析会漏判
        val r = catalogWith(FailTool(), maxResultChars = 100).invokeDetailed("FailTool", emptyMap())
        assertTrue(r.isError, "截断不能吃掉失败判定：${r.output}")
        assertTrue(r.output.contains("结果超长已截断"), r.output)
        assertTrue(r.output.length < 200, "输出仍被截断：len=${r.output.length}")
    }

    @Test
    fun `invokeDetailed non json output is not error`() {
        // 工具可能返回非 JSON 文本——解析失败不视为错误（编排层照常注入）
        val textTool = object : Tool<Map<String, Any?>, String>() {
            override val name = "TextTool"
            override val description = "功能：返回纯文本。\n何时使用：测试非 JSON 输出。"
            override val parameterHint = "JSON: {}"
            override val inputClass: Class<Map<String, Any?>> =
                @Suppress("UNCHECKED_CAST")
                Map::class.java as Class<Map<String, Any?>>
            override fun handle(input: Map<String, Any?>): String = "这是一段纯文本，不是 JSON"
        }
        val r = ToolCatalog(toolsProvider = { listOf(textTool) }).invokeDetailed("TextTool", emptyMap())
        assertFalse(r.isError, "非 JSON 输出不算错误：${r.output}")
        assertTrue(r.output.contains("纯文本"), r.output)
    }

    @Test
    fun `invoke delegates to invokeDetailed output`() {
        val catalog = catalogWith(CatalogFooTool())
        val input = mapOf<String, Any?>("keyword" to "abc")
        assertEquals(
            catalog.invokeDetailed("CatalogFooTool", input).output,
            catalog.invoke("CatalogFooTool", input),
            "invoke 必须是 invokeDetailed(...).output 的纯委托"
        )
        // 错误路径同样委托
        val unknownDetailed = catalog.invokeDetailed("Nope", emptyMap()).output
        assertEquals(unknownDetailed, catalog.invoke("Nope", emptyMap()))
    }
}

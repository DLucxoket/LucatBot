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
}

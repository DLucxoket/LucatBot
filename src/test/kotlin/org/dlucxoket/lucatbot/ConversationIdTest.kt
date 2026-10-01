package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.model.session.ConversationId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** ConversationId 值对象：解析宽容性与旧 startsWith/substringAfter 组合的行为等价 */
class ConversationIdTest {

    @Test
    fun `parse group keeps type and target`() {
        val id = ConversationId.parse("group:123456")
        assertTrue(id.isGroup)
        assertEquals("123456", id.targetId)
        assertEquals(123456L, id.targetIdOrNull)
        assertEquals("group:123456", id.asKey())
    }

    @Test
    fun `parse private keeps type and target`() {
        val id = ConversationId.parse("private:987654")
        assertFalse(id.isGroup)
        assertEquals("987654", id.targetId)
        assertEquals("private:987654", id.asKey())
    }

    @Test
    fun `unknown prefix degrades to private with bare target - legacy behavior`() {
        // 旧代码：startsWith("group:")==false → PRIVATE；substringAfter(":") 取冒号后
        val id = ConversationId.parse("foo:42")
        assertFalse(id.isGroup)
        assertEquals("42", id.targetId, "未知前缀与旧 substringAfter 语义一致")
    }

    @Test
    fun `raw without colon keeps whole string as target - legacy behavior`() {
        // 旧代码：startsWith false → PRIVATE；substringAfter(":") 无冒号返回原串
        val id = ConversationId.parse("12345")
        assertFalse(id.isGroup)
        assertEquals("12345", id.targetId, "无冒号与旧 substringAfter 语义一致")
    }

    @Test
    fun `non numeric target yields null numeric form without throwing`() {
        val id = ConversationId.parse("private:abc")
        assertNull(id.targetIdOrNull, "非数字不抛异常、由调用方决定兜底")
        assertEquals("abc", id.targetId, "字符串形态照实保留")
    }

    @Test
    fun `factories round trip through asKey`() {
        assertEquals("group:777", ConversationId.group(777).asKey())
        assertEquals("private:888", ConversationId.private("888").asKey())
        // 拼 → 解析 → 拼 是恒等的（协议收口后改格式只需动 asKey/parse）
        assertEquals(
            ConversationId.group(777),
            ConversationId.parse(ConversationId.group(777).asKey())
        )
        assertEquals(
            ConversationId.private(888),
            ConversationId.parse(ConversationId.private(888).asKey())
        )
    }
}

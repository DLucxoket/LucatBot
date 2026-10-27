package org.dlucxoket.lucatbot

import org.dlucxoket.lucatbot.bot.agent.data.ConversationAction
import org.dlucxoket.lucatbot.bot.agent.data.ConversationDecision
import org.dlucxoket.lucatbot.bot.agent.data.LlmJson
import org.dlucxoket.lucatbot.bot.agent.data.SessionAction
import org.dlucxoket.lucatbot.bot.agent.data.SessionDecision
import org.dlucxoket.lucatbot.bot.agent.data.SessionDecisionParser
import org.dlucxoket.lucatbot.bot.agent.data.SessionPick
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickParser
import org.dlucxoket.lucatbot.bot.agent.data.SessionPickType
import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.loop.MainLoopController
import org.dlucxoket.lucatbot.bot.persona.BotMood
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** SessionPickParser 对统一信封（open_conversation / idle）的解析与容错 */
class SessionPickParserTest {

    @Test
    fun `open_conversation private`() {
        val pick = SessionPickParser.parse("""{"action":"open_conversation","params":{"type":"private","target":"123"},"reason":"想找我"}""")
        assertEquals(SessionPick(SessionPickType.PRIVATE, "123"), pick)
    }

    @Test
    fun `open_conversation group`() {
        val pick = SessionPickParser.parse("""{"action":"open_conversation","params":{"type":"group","target":"456"}}""")
        assertEquals(SessionPick(SessionPickType.GROUP, "456"), pick)
    }

    @Test
    fun `open_conversation proactive`() {
        val pick = SessionPickParser.parse("""{"action":"open_conversation","params":{"type":"proactive","target":"789"}}""")
        assertEquals(SessionPick(SessionPickType.PROACTIVE, "789"), pick)
    }

    @Test
    fun `idle returns null`() {
        assertNull(SessionPickParser.parse("""{"action":"idle","reason":"好累"}"""))
    }

    @Test
    fun `invalid json returns null`() {
        assertNull(SessionPickParser.parse("not json"))
        assertNull(SessionPickParser.parse(""))
    }

    @Test
    fun `missing target returns null`() {
        assertNull(SessionPickParser.parse("""{"action":"open_conversation","params":{"type":"private"}}"""))
    }

    @Test
    fun `fenced json works`() {
        val raw = """```json
{"action":"open_conversation","params":{"type":"private","target":"1"}}
```"""
        assertEquals(SessionPick(SessionPickType.PRIVATE, "1"), SessionPickParser.parse(raw))
    }
}

/** ConversationDecision.parse 的动作映射、参数提取与非法输入兜底 */
class ConversationDecisionParseTest {

    @Test
    fun `send with content`() {
        val d = ConversationDecision.parse("""{"action":"send","params":{"content":"哈哈好的"},"reason":"想回"}""")
        assertEquals(ConversationAction.SEND, d.action)
        assertEquals("哈哈好的", d.content)
    }

    @Test
    fun `read_more with count`() {
        val d = ConversationDecision.parse("""{"action":"read_more","params":{"count":7}}""")
        assertEquals(ConversationAction.READ_MORE, d.action)
        assertEquals(7, d.readCount)
    }

    @Test
    fun `read_more default count 10`() {
        val d = ConversationDecision.parse("""{"action":"read_more"}""")
        assertEquals(10, d.readCount)
    }

    @Test
    fun `return`() {
        val d = ConversationDecision.parse("""{"action":"return","reason":"累了"}""")
        assertEquals(ConversationAction.RETURN, d.action)
    }

    @Test
    fun `stay with seconds`() {
        val d = ConversationDecision.parse("""{"action":"stay","params":{"seconds":45}}""")
        assertEquals(ConversationAction.STAY, d.action)
        assertEquals(45, d.staySeconds)
    }

    @Test
    fun `stay default seconds 30`() {
        val d = ConversationDecision.parse("""{"action":"stay"}""")
        assertEquals(ConversationAction.STAY, d.action)
        assertEquals(30, d.staySeconds)
    }

    @Test
    fun `view_images with refs`() {
        val d = ConversationDecision.parse(
            """{"action":"view_images","params":{"refs":["a.png","b.jpg"]}}"""
        )
        assertEquals(ConversationAction.VIEW_IMAGES, d.action)
        assertEquals(listOf("a.png", "b.jpg"), d.viewRefs, "看图请求解析出文件标签")
    }

    @Test
    fun `view_images without refs falls back to RETURN`() {
        // 看图请求没对象 = 无效决策（与 send 缺正文同规则）
        assertEquals(ConversationAction.RETURN,
            ConversationDecision.parse("""{"action":"view_images","params":{"refs":[]}}""").action)
        assertEquals(ConversationAction.RETURN,
            ConversationDecision.parse("""{"action":"view_images"}""").action)
    }

    @Test
    fun `send content array resolves into outgoing messages`() {
        // 分段发送：数组成员 → OOP（条级 reply/delay + 结构化 members）
        val d = ConversationDecision.parse("""{"action":"send","params":{"content":[
            {"reply": 1683970346, "delay": 2.5, "members":[
                {"type":"text","text":"哈哈"},
                {"type":"at","qq":2402567904},
                {"type":"face","id":14}
            ]},
            {"members":[{"type":"text","text":"补一句"}]}
        ]}}""")
        assertEquals(ConversationAction.SEND, d.action, "数组 content 也过 SEND 校验")

        val out = d.resolveOutgoing()
        assertEquals(2, out.size, "两条消息")
        assertEquals(1683970346L, out[0].replyToId, "条级 reply 解析为 Long")
        assertEquals(2.5, out[0].delaySeconds, "延迟秒数解析")
        assertEquals(3, out[0].members.size, "结构化段全解析")
        assertEquals("text", out[0].members[0].type)
        assertEquals("哈哈", out[0].members[0].text)
        assertEquals("2402567904", out[0].members[1].qq, "数字 qq 统一转字符串")
        assertEquals("14", out[0].members[2].id)
        assertNull(out[1].replyToId, "可选字段缺省 → null")
        assertEquals(0.0, out[1].delaySeconds, "缺省延迟为 0")
    }

    @Test
    fun `send string content stays compatible as single message`() {
        // 旧字符串写法兼容：LLM 惯性输出不炸
        val d = ConversationDecision.parse("""{"action":"send","params":{"content":"在吗"}}""")
        val out = d.resolveOutgoing()
        assertEquals(1, out.size, "字符串 → 单条")
        assertEquals("在吗", out[0].text)
        assertNull(out[0].replyToId)
        assertTrue(out[0].members.isEmpty())
    }

    @Test
    fun `send invalid array entries are dropped`() {
        // 空数组 / 无 members 无 reply 的条目 / 坏元素 → 丢弃（编排层走「无效发送」提示）
        assertEquals(0, ConversationDecision.parse(
            """{"action":"send","params":{"content":[]}}""").resolveOutgoing().size)
        assertEquals(0, ConversationDecision.parse(
            """{"action":"send","params":{"content":[{"delay":1}]}}""").resolveOutgoing().size,
            "既无成员也无 reply 的条目丢弃")
        assertEquals(1, ConversationDecision.parse(
            """{"action":"send","params":{"content":["坏元素",{"members":[{"type":"text","text":"ok"}]}]}}"""
        ).resolveOutgoing().size, "坏元素跳过、有效条目保留")
    }

    @Test
    fun `invalid falls back to RETURN`() {
        assertEquals(ConversationAction.RETURN, ConversationDecision.parse("garbage").action)
        assertEquals(ConversationAction.RETURN, ConversationDecision.parse("""{"action":"dance"}""").action)
    }

    @Test
    fun `send without content falls back to RETURN`() {
        val d = ConversationDecision.parse("""{"action":"send","params":{}}""")
        assertEquals(ConversationAction.RETURN, d.action)
    }

    @Test
    fun `reason not in params`() {
        val d = ConversationDecision.parse("""{"action":"send","params":{"content":"hi"},"reason":"内心戏"}""")
        assertEquals("hi", d.content)
        assertNull(d.params["reason"])
        assertEquals("内心戏", d.reason)
    }

    @Test
    fun `parses remember todo_add todo_done`() {
        val d = ConversationDecision.parse(
            """{"action":"send","params":{"content":"好的，我盯着"},"remember":"他叫我周三提醒他交房租","todo_add":"帮他看群","todo_done":"取快递"}"""
        )
        assertEquals(ConversationAction.SEND, d.action)
        assertEquals("他叫我周三提醒他交房租", d.remember)
        assertEquals("帮他看群", d.todo_add)
        assertEquals("取快递", d.todo_done)
    }

    @Test
    fun `side effect fields optional and blank dropped`() {
        val d = ConversationDecision.parse("""{"action":"return","reason":"累了"}""")
        assertNull(d.remember)
        assertNull(d.todo_add)
        assertNull(d.todo_done)

        val blank = ConversationDecision.parse(
            """{"action":"send","params":{"content":"hi"},"remember":"","todo_add":"  ","todo_done":null}"""
        )
        assertNull(blank.remember)
        assertNull(blank.todo_add)
        assertNull(blank.todo_done)
    }

    @Test
    fun `list_tools with and without query`() {
        val all = ConversationDecision.parse("""{"action":"list_tools","reason":"先看看"}""")
        assertEquals(ConversationAction.LIST_TOOLS, all.action)
        assertNull(all.toolQuery)

        val searched = ConversationDecision.parse("""{"action":"list_tools","params":{"query":" 记忆 "}}""")
        assertEquals(ConversationAction.LIST_TOOLS, searched.action)
        assertEquals("记忆", searched.toolQuery)
    }

    @Test
    fun `tool action parses name and input`() {
        val d = ConversationDecision.parse(
            """{"action":"tool","params":{"name":"GroupListTool","input":{"keyword":"开发"}},"reason":"查群"}"""
        )
        assertEquals(ConversationAction.CALL_TOOL, d.action)
        assertEquals("GroupListTool", d.toolName)
        assertEquals("开发", d.toolInput["keyword"])
    }

    @Test
    fun `tool action without name falls back to RETURN`() {
        val d = ConversationDecision.parse("""{"action":"tool","params":{"input":{}}}""")
        assertEquals(ConversationAction.RETURN, d.action, "缺工具名必须兜底退出，不能带病执行")
        assertNull(d.toolName)
    }

    @Test
    fun `tool action without input gives empty map`() {
        val d = ConversationDecision.parse("""{"action":"tool","params":{"name":"X"}}""")
        assertEquals(ConversationAction.CALL_TOOL, d.action)
        assertTrue(d.toolInput.isEmpty())
    }

    @Test
    fun `parse accepts tool-call style envelope with truncated params tail`() {
        // 实盘 02:30：模型把 send 包成「函数调用」标记，且 params JSON 少了尾巴（] }）——
        // 内容其实是好的，翻译 + 补尾后照常解析，不再整轮兜底退出
        val lt = "<"
        val raw = lt + "function=send>" +
            lt + "parameter name=\"params\">" +
            "{\"content\": [{\"reply\": \"#123\", \"members\": [{\"type\": \"text\", \"text\": \"在吗\"}], " +
            "\"wait_message\": 120}" +   // ← 少了 ] }
            lt + "/parameter>" +
            lt + "parameter name=\"reason\">俏皮回一句并催他去睡" + lt + "/parameter>" +
            lt + "/function>"

        val d = ConversationDecision.parse(raw)

        assertEquals(ConversationAction.SEND, d.action, "函数调用形态翻译成 send")
        assertEquals("俏皮回一句并催他去睡", d.reason, "reason 原样取出")
        val out = d.resolveOutgoing()
        assertEquals(1, out.size, "params 缺尾被修复，条目可用")
        assertTrue(out.single().summarize().contains("在吗"), "正文可用：${out.single().summarize()}")
        assertEquals(120, d.waitMessageSeconds, "错位写进 content 条目的 wait_message 也取到")
    }

    @Test
    fun `parse tolerates stray equals after colon`() {
        // 实盘 14:35：模型把 "reason" 后写成 :=（多一个 =）→ 整轮被判非 JSON 兜底退出
        val raw = """{"action":"send","params":{"content":"在吗"},"reason":="接寒凌名梗，调侃一句"}"""
        val d = ConversationDecision.parse(raw)
        assertEquals(ConversationAction.SEND, d.action, "清洗手误后正常解析")
        assertEquals("接寒凌名梗，调侃一句", d.reason)
        assertEquals("在吗", d.resolveOutgoing().single().summarize())
    }

    @Test
    fun `memory_queries written as action falls back to stay so search still runs`() {
        // 实盘 15:46 / 15:48：模型把顶层字段当成了 action 直接输出 → 不作废本轮，
        // 落到 stay 2s（当轮照常搜索、下一轮拿到结果再决策）
        val bare = ConversationDecision.parse(
            """{"action":"memory_queries","memory_queries":["921867047","QQ921867047 谁啊"]}"""
        )
        assertEquals(ConversationAction.STAY, bare.action, "落到 stay 等搜索结果")
        assertEquals(2, bare.staySeconds, "短停留")
        assertEquals(listOf("921867047", "QQ921867047 谁啊"), bare.memoryQueries, "关键词照常取")

        val fenced = ConversationDecision.parse(
            "```json\n{\"action\":\"memory_queries\",\"memory_queries\":[\"疯疯\"]}\n```"
        )
        assertEquals(ConversationAction.STAY, fenced.action, "裹了 markdown 也认")
        assertEquals(listOf("疯疯"), fenced.memoryQueries)

        val withParams = ConversationDecision.parse(
            """{"action":"memory_queries","params":{"count":10},"reason":"先搜搜背景"}"""
        )
        assertEquals(ConversationAction.STAY, withParams.action)
        assertEquals("先搜搜背景", withParams.reason, "reason 保留")
    }

    @Test
    fun `searchPause marks stays that wait for own search results`() {
        // searchPause 置位三态：memory-alias 合成 stay / 真 stay 带搜索词 / markdown 包裹形态
        val synthesized = ConversationDecision.parse(
            """{"action":"memory_queries","memory_queries":["可乐"]}"""
        )
        assertEquals(ConversationAction.STAY, synthesized.action, "合成 stay")
        assertEquals(2, synthesized.staySeconds, "合成停留压到 2 秒")
        assertTrue(synthesized.searchPause, "memory-alias 合成的 stay 是搜索型停留")

        val fenced = ConversationDecision.parse(
            "```json\n{\"action\":\"search_memory\",\"memory_queries\":[\"疯疯\"]}\n```"
        )
        assertEquals(ConversationAction.STAY, fenced.action, "裹 markdown 的搜索别名同样落 stay")
        assertTrue(fenced.searchPause, "markdown 包裹形态同样置位")

        val realStay = ConversationDecision.parse(
            """{"action":"stay","params":{"seconds":1},"memory_queries":["白鸽"]}"""
        )
        assertTrue(realStay.searchPause, "带 memory_queries 的真 stay 是搜索型停留")

        // 对照组：普通 stay 是「没人接话的干等」（走 stayStreak）；非 stay 动作带搜索词也不算停留
        val plainStay = ConversationDecision.parse("""{"action":"stay","params":{"seconds":5}}""")
        assertFalse(plainStay.searchPause, "普通 stay 不置位（仍计 stayStreak）")
        val sendWithQueries = ConversationDecision.parse(
            """{"action":"send","params":{"content":"hi"},"memory_queries":["x"]}"""
        )
        assertFalse(sendWithQueries.searchPause, "send 不是停留，不置位")
    }

    @Test
    fun `isFallback is an explicit field not reference identity`() {
        // 显式字段：FALLBACK.copy(...) 派生路径也必须带着标记，重试循环才不会静默失效
        val fallback = ConversationDecision.parse("根本不是 JSON")
        assertTrue(fallback.isFallback, "解析失败的兜底结果带标记")
        assertTrue(ConversationDecision.FALLBACK.isFallback, "FALLBACK 常量带标记")
        assertFalse(
            ConversationDecision.parse("""{"action":"return","reason":"正常"}""").isFallback,
            "正常解析结果不带标记"
        )
        assertFalse(
            ConversationDecision.forceReturn("护栏强制退出").isFallback,
            "编排层强制 return 不是解析失败（不该触发 Agent 重试）"
        )
    }

    @Test
    fun `function tag with flat params becomes tool call`() {
        // 支持形态：function 名不认识 → 按工具调用；参数可用裸标签对或 parameter 标签
        val lt = "<"
        val bare = lt + "function=GetMsgTool>" +
            lt + "messageId>1377891508" + lt + "/messageId>" +
            lt + "/function>"
        val d = ConversationDecision.parse(bare)
        assertEquals(ConversationAction.CALL_TOOL, d.action, "不认识的名字 → 工具调用")
        assertEquals("GetMsgTool", d.toolName)
        assertEquals("1377891508", d.toolInput["messageId"]?.toString(), "裸参数对取值")

        val parameter = lt + "invoke name=\"GetMsgTool\">" +
            lt + "parameter name=\"messageId\">1377891508" + lt + "/parameter>" +
            lt + "/invoke>"
        val d2 = ConversationDecision.parse(parameter)
        assertEquals(ConversationAction.CALL_TOOL, d2.action)
        assertEquals("GetMsgTool", d2.toolName)
        assertEquals("1377891508", d2.toolInput["messageId"]?.toString())

        // 认识的名字仍是动作（不是工具）
        val action = lt + "function=stay>" + lt + "seconds>10" + lt + "/seconds>" + lt + "/function>"
        val d3 = ConversationDecision.parse(action)
        assertEquals(ConversationAction.STAY, d3.action, "已知动作名照常当动作")
        assertEquals(10, d3.staySeconds, "裸参数对进 params")
    }

    @Test
    fun `fetch url tool call in harness format parses to input`() {
        // 其他 agent 都长这样：function=工具名 + parameter 标签（双引号与单引号都收）
        val lt = "<"
        val url = "https://www.bing.com/search?q=%E5%8E%9F%E7%A5%9E"
        val raw = lt + "function=FetchUrlTool>" +
            lt + "parameter name=\"url\">" + url + lt + "/parameter>" +
            lt + "parameter name='maxChars'>3000" + lt + "/parameter>" +
            lt + "/function>"
        val d = ConversationDecision.parse(raw)

        assertEquals(ConversationAction.CALL_TOOL, d.action, "翻译成 tool 动作")
        assertEquals("FetchUrlTool", d.toolName)
        assertEquals(url, d.toolInput["url"]?.toString(), "URL 原样（含 % 转义）")
        assertEquals(3000, (d.toolInput["maxChars"] as Number).toInt(), "数字参数按数字")

        // 顶层字段写在参数标签里也提回顶层（todo_add 等）
        val withTodo = lt + "function=stay>" +
            lt + "parameter name=\"todo_add\">买票" + lt + "/parameter>" + lt + "/function>"
        assertEquals("买票", ConversationDecision.parse(withTodo).todo_add, "todo_add 提回顶层")
    }

    @Test
    fun `tool input tolerates flat params and double encoded string`() {
        // 实盘 SwitchConversationTool 连调两次报错：模型把参数平铺在 params 顶层、不包 input
        val flat = ConversationDecision.parse(
            """{"action":"tool","params":{"name":"SwitchConversationTool","type":"private","target":"456"}}"""
        )
        assertEquals("456", flat.toolInput["target"]?.toString(), "平铺参数兜底进 input")
        assertEquals("private", flat.toolInput["type"]?.toString())
        assertFalse(flat.toolInput.containsKey("name"), "name 不混进 input")

        val dbl = ConversationDecision.parse(
            """{"action":"tool","params":{"name":"X","input":"{\"q\":\"hi\"}"}}"""
        )
        assertEquals("hi", dbl.toolInput["q"]?.toString(), "input 被双层编码也能解开")

        val normal = ConversationDecision.parse(
            """{"action":"tool","params":{"name":"X","input":{"q":"yo"}}}"""
        )
        assertEquals("yo", normal.toolInput["q"]?.toString(), "正常对象照旧")
    }

    @Test
    fun `unusable input shapes are dropped instead of leaking the key`() {
        // 实盘 22:33 GroupRequestTool 报 UnrecognizedPropertyException 的根因：
        // input 为不可用形态（null/数字/裸词）时旧兜底把 input 键本身平铺进 DTO。
        // 不可用的包装字段没有信息量——丢弃后由 DTO 默认值兜底。
        val nullInput = ConversationDecision.parse(
            """{"action":"tool","params":{"name":"X","input":null,"action":"list"}}"""
        )
        assertFalse(nullInput.toolInput.containsKey("input"), "null input 不泄漏键：${nullInput.toolInput}")

        val numberInput = ConversationDecision.parse(
            """{"action":"tool","params":{"name":"X","input":123}}"""
        )
        assertFalse(numberInput.toolInput.containsKey("input"), "数字 input 不泄漏键")

        // input 是非 JSON 对象的字符串（如裸词 "list"）→ 解不开 → 同样丢弃
        val bareWord = ConversationDecision.parse(
            """{"action":"tool","params":{"name":"X","input":"list"}}"""
        )
        assertFalse(bareWord.toolInput.containsKey("input"), "裸词 input 不泄漏键：${bareWord.toolInput}")

        // 平铺参数（可用信息）照旧保留——只丢不可用的 input 键本身
        val flat = ConversationDecision.parse(
            """{"action":"tool","params":{"name":"X","input":null,"id":"7","action":"list"}}"""
        )
        assertEquals("7", flat.toolInput["id"]?.toString(), "同层可用字段保留")
    }

    @Test
    fun `plain json is not mistaken for tool-call envelope`() {
        val d = ConversationDecision.parse("""{"action":"return","reason":"累了"}""")
        assertEquals(ConversationAction.RETURN, d.action)
        assertEquals("累了", d.reason)
    }

    @Test
    fun `memory_queries parses array and defaults to empty`() {
        val d = ConversationDecision.parse(
            """{"action":"send","params":{"content":"在吗"},"memory_queries":["碳酸猫","271524789","可乐"]}"""
        )
        assertEquals(listOf("碳酸猫", "271524789", "可乐"), d.memoryQueries, "关键词数组原样取出")

        assertTrue(ConversationDecision.parse("""{"action":"return"}""").memoryQueries.isEmpty(), "缺字段 → 空")
        assertTrue(ConversationDecision.parse("""{"action":"return","memory_queries":[]}""").memoryQueries.isEmpty(), "空数组 → 空")
        assertTrue(
            ConversationDecision.parse("""{"action":"return","memory_queries":"可乐"}""").memoryQueries.isEmpty(),
            "非数组 → 空（不动记忆段）"
        )
        assertTrue(
            ConversationDecision.parse("""{"action":"return","memory_queries":["  ",null," 可乐 "]}""")
                .memoryQueries == listOf("可乐"),
            "空白与 null 过滤、trim"
        )
    }
}

/** SessionDecisionParser：消息列表信封的四种动作与垃圾输出兜底 */
class SessionDecisionParserTest {

    @Test
    fun `open_conversation yields OPEN with pick`() {
        val d = SessionDecisionParser.parse(
            """{"action":"open_conversation","params":{"type":"group","target":"789"},"reason":"有未读"}"""
        )
        assertEquals(SessionAction.OPEN_CONVERSATION, d.action)
        assertEquals(SessionPick(SessionPickType.GROUP, "789"), d.pick)
    }

    @Test
    fun `idle yields IDLE`() {
        assertEquals(SessionAction.IDLE, SessionDecisionParser.parse("""{"action":"idle"}""").action)
    }

    @Test
    fun `idle carries llm-chosen sleep seconds`() {
        // 空闲时长由 LLM 决定：给数就用、没给/坏值/负数回退随机兜底
        val given = SessionDecisionParser.parse(
            """{"action":"idle","params":{"seconds":600},"reason":"没人理，睡会儿"}"""
        )
        assertEquals(600, given.idleSeconds, "LLM 指定的空闲秒数")
        assertEquals(
            120,
            SessionDecisionParser.parse("""{"action":"idle","params":{"seconds":"120"}}""").idleSeconds,
            "数字字符串也收"
        )
        assertEquals(0, SessionDecisionParser.parse("""{"action":"idle"}""").idleSeconds, "没给 → 随机兜底")
        assertEquals(
            0,
            SessionDecisionParser.parse("""{"action":"idle","params":{"seconds":"abc"}}""").idleSeconds,
            "坏值 → 随机兜底"
        )
        assertEquals(
            0,
            SessionDecisionParser.parse("""{"action":"idle","params":{"seconds":-5}}""").idleSeconds,
            "负数归零"
        )
        assertEquals(
            0, SessionDecisionParser.parse("not json").idleSeconds, "兜底路径不带秒数"
        )
    }

    @Test
    fun `list_tools with and without query`() {
        val all = SessionDecisionParser.parse("""{"action":"list_tools"}""")
        assertEquals(SessionAction.LIST_TOOLS, all.action)
        assertNull(all.query)

        val searched = SessionDecisionParser.parse("""{"action":"tools","params":{"query":"群"}}""")
        assertEquals(SessionAction.LIST_TOOLS, searched.action)
        assertEquals("群", searched.query)
    }

    @Test
    fun `tool yields CALL_TOOL with name and input`() {
        val d = SessionDecisionParser.parse(
            """{"action":"tool","params":{"name":"FriendListTool","input":{"keyword":"张"}}}"""
        )
        assertEquals(SessionAction.CALL_TOOL, d.action)
        assertEquals("FriendListTool", d.toolName)
        assertEquals("张", d.toolInput["keyword"])
    }

    @Test
    fun `garbage and invalid tool fall back to IDLE`() {
        // 垃圾输出 / 未知 action / 缺工具名 / open 参数无效 → 全部兜底空闲，绝不卡死主循环
        assertEquals(SessionAction.IDLE, SessionDecisionParser.parse("not json").action)
        assertEquals(SessionAction.IDLE, SessionDecisionParser.parse("""{"action":"fly"}""").action)
        assertEquals(SessionAction.IDLE, SessionDecisionParser.parse("""{"action":"tool","params":{}}""").action)
        assertEquals(
            SessionAction.IDLE,
            SessionDecisionParser.parse("""{"action":"open_conversation","params":{"type":"private"}}""").action
        )
    }

    @Test
    fun `invalid open yields IDLE not crash`() {
        val d: SessionDecision = SessionDecisionParser.parse("""{"action":"open_conversation","params":{}}""")
        assertEquals(SessionAction.IDLE, d.action)
        assertNull(d.pick)
    }

    @Test
    fun `todo side effect fields survive any action including fallbacks`() {
        // 顶层 todo_add/todo_done 与 action 无关：任何动作（含兜底 idle）都携带
        val open = SessionDecisionParser.parse(
            """{"action":"open_conversation","params":{"type":"private","target":"1"},"todo_add":"买票","todo_done":"取快递"}"""
        )
        assertEquals("买票", open.todoAdd)
        assertEquals("取快递", open.todoDone)

        val idle = SessionDecisionParser.parse("""{"action":"idle","todo_add":"买票"}""")
        assertEquals("买票", idle.todoAdd)
        assertNull(idle.todoDone)

        // 参数无效兜底 → 待办字段照样保留（LLM 已说要记的事不能丢）
        val fallback = SessionDecisionParser.parse(
            """{"action":"open_conversation","params":{},"todo_add":"买票"}"""
        )
        assertEquals(SessionAction.IDLE, fallback.action)
        assertEquals("买票", fallback.todoAdd, "兜底不丢副作用字段")

        // 缺字段 → null；空白 → null
        assertNull(SessionDecisionParser.parse("""{"action":"idle"}""").todoAdd)
        assertNull(SessionDecisionParser.parse("""{"action":"idle","todo_add":"  "}""").todoAdd)
    }

    @Test
    fun `todo_add_time parses leniently and never drops the todo`() {
        // 完整日期 → 毫秒
        val d = ConversationDecision.parse(
            """{"action":"return","todo_add":"去XX群看看","todo_add_time":"2026-10-07 14:30"}"""
        )
        assertEquals("去XX群看看", d.todo_add)
        assertNotNull(d.todoAddTime)

        // 认不出的时间 → null，**但待办照留**（绝不能因为时间格式丢事）
        val bad = ConversationDecision.parse(
            """{"action":"return","todo_add":"提醒他交房租","todo_add_time":"明天下午"}"""
        )
        assertEquals("提醒他交房租", bad.todo_add, "时间烂也不丢待办")
        assertNull(bad.todoAddTime)

        // 没给时间 → null（老行为不变）
        assertNull(ConversationDecision.parse("""{"action":"return","todo_add":"x"}""").todoAddTime)

        // 选会话侧同款（出口统一 attach，任何动作都带得上）
        val s = SessionDecisionParser.parse(
            """{"action":"idle","todo_add":"买票","todo_add_time":"2026-10-07 14:30"}"""
        )
        assertEquals("买票", s.todoAdd)
        assertNotNull(s.todoAddTime)
        assertEquals(SessionAction.IDLE, s.action, "带时间不影响动作解析")
    }
}

/** LlmJson 剥离 markdown 代码块、提取 JSON 的边界测试 */
class LlmJsonTest {

    @Test
    fun `extract strips fence and noise`() {
        val raw = "好的，我的选择是\n```json\n{\"action\":\"idle\"}\n```\n以上"
        assertTrue(LlmJson.extract(raw).startsWith("{"))
    }

    @Test
    fun `paramsOf empty when missing`() {
        val node = LlmJson.parseTree("""{"action":"idle"}""")!!
        assertTrue(LlmJson.paramsOf(node).isEmpty())
    }

    @Test
    fun `paramsOf returns map`() {
        val node = LlmJson.parseTree("""{"action":"send","params":{"content":"hi","count":3}}""")!!
        val p = LlmJson.paramsOf(node)
        assertEquals("hi", p["content"])
        assertEquals(3, p["count"])
    }

    @Test
    fun `parseTree repairs truncated json tail`() {
        // 实盘：模型把 JSON 尾巴写丢（少 ] }）——补尾后照常解析，别整轮作废
        val node = LlmJson.parseTree("""{"content": [{"text": "在吗"}""")
        assertNotNull(node, "缺尾应能修复")
        assertTrue(node!!.has("content"), "修复后字段完整：$node")
        assertEquals("在吗", node.get("content")[0].get("text").asText())
    }

    @Test
    fun `repairJson keeps intact json unchanged`() {
        val ok = """{"a":[1,2]}"""
        assertEquals(ok, LlmJson.repairJson(ok), "本来就好不动它")
    }

    @Test
    fun `parseLenient fixes stray equals and truncated tail`() {
        assertNotNull(LlmJson.parseLenient("""{"a":="b"}"""), "冒号后多写 = 清掉")
        assertNotNull(LlmJson.parseLenient("""{"a": [1, 2"""), "缺尾补闭合")
        assertEquals("b", LlmJson.parseLenient("""{"a":="b"}""")!!.get("a").asText())
    }

    @Test
    fun `stray quote plus zero width space in text no longer kills the round`() {
        // 实盘 01:06：模型想在正文里带 `"` 没转义、还夹了零宽空格 → 字符串提前闭合，
        // 整轮解析作废兜底退出（这条「自嘲收场」的消息根本没发出去）
        val raw = "{\n" +
            "\"action\": \"send\",\n" +
            "\"params\": {\n" +
            "  \"content\": [\n" +
            "    {\"delay\": 3, \"members\": [{\"type\": \"text\", \"text\": \"行行行，我自作多情了，本来也没有\"\u200B\"}]}\n" +
            "  ]\n" +
            "},\n" +
            "\"reason\": \"自嘲收场\"\n" +
            "}"
        val node = LlmJson.parseLenient(raw)!!
        val text = node.get("params").get("content").get(0).get("members").get(0).get("text").asText()
        assertEquals("行行行，我自作多情了，本来也没有\"", text, "裸引号转义进正文、零宽空格剔除")
    }

    @Test
    fun `invisible chars in structural positions are stripped`() {
        val node = LlmJson.parseLenient("{\u200B\"action\": \"return\",\u202E\"reason\": \"正常\"}")!!
        assertEquals("return", node.get("action").asText())
        assertEquals("正常", node.get("reason").asText(), "结构位的零宽/双向控制符剔除后照常解析")
    }

    @Test
    fun `value text containing quoted colon is escaped not treated as key close`() {
        val node = LlmJson.parseLenient(
            """{"action": "send", "params": {"content": [{"members": [{"type": "text", "text": "他说:"别"，行"}]}]}}"""
        )!!
        val text = node.get("params").get("content").get(0).get("members").get(0).get("text").asText()
        assertEquals("""他说:"别"，行""", text, "值串里的引号+冒号按正文转义，不误判成键闭合")
    }

    @Test
    fun `escapeStrayQuotes is identity on valid json`() {
        val ok = """{"a": "x", "b": ["y", {"c": "他说:\"这样\"，行"}], "他说": "别"}"""
        assertEquals(ok, LlmJson.escapeStrayQuotes(ok), "合法 JSON 必须原样（含转义引号与中文键）")
    }

    @Test
    fun `ConversationDecision survives stray quote payload instead of falling back`() {
        val raw = "{\"action\": \"send\", \"params\": {\"content\": [{\"delay\": 3, " +
            "\"members\": [{\"type\": \"text\", \"text\": \"行行行，本来也没有\"\u200B\"}]}]}}"
        val d = ConversationDecision.parse(raw)
        assertEquals(ConversationAction.SEND, d.action, "修复后照常取到动作")
        assertFalse(d.isFallback, "裸引号修复后不再兜底退出")
    }

    @Test
    fun `toolCallEnvelope returns null for plain output`() {
        // knownActions 词表参数化（词表单一源自枚举伴生，LlmJson 不再自带副本）
        assertNull(
            LlmJson.toolCallEnvelope("""{"action":"return"}""", ConversationAction.WIRE_NAMES),
            "纯 JSON 不误判"
        )
        assertNull(
            LlmJson.toolCallEnvelope("随便一句话", SessionAction.WIRE_NAMES),
            "无标记不误判"
        )
    }

    @Test
    fun `known actions vocabulary is single sourced from enums`() {
        // 词表单一源：prompt 动作行、parse、标签形态翻译都从枚举伴生取——
        // 新增 action 只改枚举，四处拷贝（含 LlmJson 曾经的 KNOWN_ACTIONS）已收敛
        assertEquals(
            "\"read_more\" | \"send\" | \"stay\" | \"return\" | \"list_tools\" | \"tool\" | \"view_images\"",
            ConversationAction.promptList(),
            "prompt 动作行渲染必须与历史字面量逐字一致"
        )
        assertEquals(
            "\"open_conversation\" | \"idle\" | \"list_tools\" | \"tool\"",
            SessionAction.promptList(),
            "选会话 prompt 动作行同上"
        )
        assertEquals(
            "read_more / send / stay / return / list_tools / tool / view_images",
            ConversationAction.promptList(separator = " / ", quoted = false),
            "重试 nudge 的无引号斜杠形式"
        )
        // 别名一并进词表（标签形态翻译判定用），且 fromWire 能解析
        assertTrue("readmore" in ConversationAction.WIRE_NAMES)
        assertTrue("memory_queries" in ConversationAction.WIRE_NAMES)
        assertTrue("call_tool" in SessionAction.WIRE_NAMES)
        assertEquals(ConversationAction.READ_MORE, ConversationAction.fromWire("readmore"))
        assertEquals(SessionAction.LIST_TOOLS, SessionAction.fromWire("tools"))
        assertNull(ConversationAction.fromWire("dance"), "词表外 → null（解析失败兜底）")
    }
}

/** 滚动操作日志：行数上限、时间戳与导出 */
class ActionLogTest {

    @Test
    fun `append and dump`() {
        val log = ActionLog()
        log.append("查看消息列表（2条未读）")
        log.append("进入私聊 用户A(123)")
        val dump = log.dump()
        assertTrue(dump.contains("查看消息列表"))
        assertTrue(dump.contains("进入私聊"))
        assertTrue(dump.contains("["))  // 带时间
    }

    @Test
    fun `keeps only recent 5 lines`() {
        // 窗口 = 原 20 的 1/4（每行都随 prompt 重发，应主人要求瘦身）
        val log = ActionLog()
        repeat(30) { log.append("动作$it") }
        assertEquals(5, log.size())
        val dump = log.dump()
        assertFalse(dump.contains("动作0"))
        assertFalse(dump.contains("动作24"), "第 6 新之前的都被滚出窗口")
        assertTrue(dump.contains("动作25"), "只保留最近 5 条")
        assertTrue(dump.contains("动作29"))
    }

    @Test
    fun `empty dump placeholder`() {
        assertEquals("（暂无）", ActionLog().dump())
    }
}

/** 空闲休眠时长的心情加权计算 */
class IdleMillisTest {

    @Test
    fun `range is 10 min to 2 h`() {
        // 心情随机兜底与 LLM 钳制同档（10 分钟 ~ 2 小时，2026-10-01 应主人要求调长）
        val random = java.util.Random(42)
        BotMood.MoodType.values().forEach { type ->
            val mood = BotMood(type, 0.8)
            repeat(20) {
                val ms = MainLoopController.idleMillis(mood, random)
                assertTrue(ms in MainLoopController.MIN_IDLE_MS..MainLoopController.MAX_IDLE_MS,
                    "$type idle $ms out of range")
            }
        }
        assertEquals(10 * 60 * 1000L, MainLoopController.MIN_IDLE_MS, "下限 10 分钟")
        assertEquals(2 * 60 * 60 * 1000L, MainLoopController.MAX_IDLE_MS, "上限 2 小时")
    }

    @Test
    fun `happy is shorter than sleepy`() {
        val random = java.util.Random(7)
        var happySum = 0L
        var sleepySum = 0L
        repeat(200) {
            happySum += MainLoopController.idleMillis(BotMood(BotMood.MoodType.HAPPY, 0.5), random)
            sleepySum += MainLoopController.idleMillis(BotMood(BotMood.MoodType.SLEEPY, 0.5), random)
        }
        assertTrue(happySum < sleepySum, "happy=$happySum sleepy=$sleepySum")
    }

    @Test
    fun `resolveIdleMillis prefers llm seconds with clamp and falls back to random`() {
        val mood = BotMood(BotMood.MoodType.NORMAL, 0.5)
        val random = java.util.Random(11)

        assertEquals(600_000L, MainLoopController.resolveIdleMillis(600, mood, random), "LLM 给的秒数直接用（600s=下限）")
        assertEquals(
            MainLoopController.MAX_LLM_IDLE_SECONDS * 1000L,
            MainLoopController.resolveIdleMillis(99_999, mood, random), "超上限钳到 7200s"
        )
        assertEquals(
            MainLoopController.MIN_LLM_IDLE_SECONDS * 1000L,
            MainLoopController.resolveIdleMillis(1, mood, random), "低于下限钳到 600s（短值也抬到 10 分钟）"
        )
        assertEquals(600, MainLoopController.MIN_LLM_IDLE_SECONDS, "LLM 钳制下限 10 分钟")
        assertEquals(7200, MainLoopController.MAX_LLM_IDLE_SECONDS, "LLM 钳制上限 2 小时")
        val fallback = MainLoopController.resolveIdleMillis(0, mood, random)
        assertTrue(fallback in MainLoopController.MIN_IDLE_MS..MainLoopController.MAX_IDLE_MS,
            "没给 → 按心情随机兜底：$fallback")
    }
}

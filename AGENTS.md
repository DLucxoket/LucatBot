# AGENTS.md

## 项目概览

Kotlin Spring Boot 4.0.3 应用——基于 OneBot 协议（QQ）的模拟真人聊天机器人，使用 Shiro 框架 + LangChain4j 的 LLM 智能体系统。

## 架构

### 智能体系统（核心设计）

机器人按**固定脚本的主循环**驱动，**核心设计原则：模拟真人在 QQ 中的真实行为**：

- `Agent<I, O>`（泛型抽象类）——基类；每个 Agent 声明自己的输入/输出 OOP 类型，实现 `handle(input: I): O`。
- 主流程只有两个智能体：`SessionPickerAgent`（消息列表选会话）与 `ConversationAgent`（会话内决策），由 `MainLoopController` / `MessageListController` / `ConversationController` 按固定脚本调用。两者都带 `list_tools`（查/搜工具目录）与 `tool`（按名调用任意 Tool）两个决策动作——由编排层经 `ToolCatalog` 执行（SendMessageTool 等编排专属工具被排除，发送必须走 send 动作记账），结果经 `ToolNotes` 攒取（单份目录去重、**10KB 字符预算**——P1 token 精简从 16KB 收紧、只限连续报错的硬上限，正常工具轮不限）注入下一轮 **user 消息**（固定脚本内嵌工具选择，**不是**自由调度链路）。
- 代码中不再保留自由调度链路（无 DispatcherAgent / AgentChain）。

### JSON ↔ OOP 边界约定

```
LLM API 边界（JSON 字符串）
    ↓ fromJson() 反序列化
Agent.handle(input: I): O   ← 纯 OOP 操作，类型安全
    ↓ toJson() 序列化
LLM API 边界（JSON 字符串）
```

- **LLM API 层**：输入/输出均为 JSON 字符串（LLM 天然读写文本）
- **Agent 内部**：反序列化为 OOP 对象操作（data class，`val` 不可变）
- **边界转换**：由 `Agent<I,O>` 基类的 `process()` 模板方法处理，子类只写 OOP 逻辑
- **数据类**：放在 `bot/agent/data/` 包，每个 Agent 定义自己的 Request/Result
- **system/user 消息拆分**：`llm.ask(systemPrompt, question)` 调用时，指令与稳定状态（人格、状态、输出格式、约束、隔离句）放 **system 消息**，剧情数据（聊天记录、未读列表、记忆内容）放 **user 消息**——避免模型把 user 里的引导语当成聊天对象的话去回应。

### Tool 与 Agent 的区别

- **Agent**：LLM 驱动的智能处理单元，可以调用 LLM 做判断和生成（如 `ConversationAgent`）
- **Tool**：确定性的数据查询/交互操作，不调用 LLM（如 ChatHistoryTool）
- Tool 仍通过 ClassGraph 自动发现（`ToolDiscovery` 缓存**单例实例列表**——发现/绑定/调用共享同一实例），由 `ToolDependencyBinder` 绑定 Bot 依赖；主循环经 `processObject` 调用 Tool，智能体经 `ToolCatalog`（目录/搜索/安全调用，异常转错误 JSON）调用 Tool。**错误契约**：`handle` 返回带 `error` 的结果时必须同时 `success=false`（`ToolErrorContractTest` 全量守卫）。

### 消息模型层级

```
Event（接口）
└─ MessageEvent（抽象类，持有 Message + sender + timestamp）
   ├─ FriendMessageEvent
   ├─ GroupMessageEvent（附加 group）
   └─ PrivateMessageEvent

Message（接口）
└─ ChatMessage（持有 List<MessageMember>）
   └─ message/member/：TextMessageMember、AtMessageMember、ImageMessageMember、FaceMessageMember、MemeMessageMember

User：qqNumber、nickname、sex、level、signature
Session：会话上下文（预留）
```

### 入口点

`adapter/onebot/MainPlugin`（Shiro BotPlugin）——`onAnyMessage` 走 `ShiroConverter` 转换 → 喂给状态机（`LucatBot.handleMessage`）并启动 `MainLoopController` 主循环；主循环按固定脚本调 `SessionPickerAgent` / `ConversationAgent` 与 Tool 完成回复。戳一戳旁路直接构造 `ConversationRequest` 调 `ConversationAgent`。

### LLM 配置

**多平台**：`application.yaml` 的 `llm.active` 选平台（`platforms` 下的 key），平台下各自配 地址/密钥/模型/输出上限/单价——**换平台改一行重启生效**：

```yaml
llm:
  active: mimo            # ← 平台选择：mimo | intern | siliconflow
  platforms:
    mimo:
      base-url: "https://token-plan-cn.xiaomimimo.com/v1"
      api-key: ${MIMO_API_KEY:${LLM_API_KEY:api-key}}   # 平台专属 env，兜底共用 LLM_API_KEY
      model-name: "mimo-v2.6-flash"
      max-tokens: 4096
      pricing:              # 元/百万 token（官网价）；留空 = 面板不显示估算费用
        input-per-1m:
        output-per-1m:
        cached-per-1m:
```

使用自研 `OpenAIAPI`（`bot/llm/`）——任何兼容 OpenAI 协议的端点均可。

## OOP 设计原则（必须遵守）

### 1. JSON 仅在 LLM API 边界

Agent/Tool 内部**一律使用 OOP 对象**（data class），不在业务逻辑里拼 JSON 字符串。JSON 序列化/反序列化只发生在 `Agent.process()` 模板方法的边界处。

```kotlin
// ✅ 正确：handle() 用 OOP 对象；指令进 system、剧情数据进 user
override fun handle(input: ConversationRequest): ConversationDecision {
    val systemPrompt = buildSystemPrompt(input)   // 人格/状态/格式/约束
    val userContent = buildUserContent(input)     // 聊天记录等剧情数据
    val llmOutput = llm.ask(systemPrompt, userContent)
    return ConversationDecision.parse(llmOutput)
}

// ❌ 错误：业务逻辑里拼 JSON
override fun handle(input: String): String {
    return """{"reply": "hello"}"""
}
```

### 2. 单一职责（SRP）

每个类只做一件事。新建类时思考："这个类是否只因一个原因而改变？"

### 3. 依赖倒置（DIP）

高层模块不依赖低层细节。`Agent.llm` 不应硬编码 `LLM.DEFAULT`——应支持构造注入或 setter。`Tool.bot` 同理。

```kotlin
// ✅ 正确：可替换的依赖
abstract class Agent<I, O>(protected val llm: LLM = LLM.DEFAULT)

// ❌ 错误：硬依赖全局单例
val llm: LLM = LLM.DEFAULT  // 无法在测试中 mock
```

### 4. 开闭原则（OCP）

加新 Tool 不应改已有代码——ClassGraph 自动发现已满足。主循环的编排改动集中在对应 Controller。

### 5. 不可变优先

数据类一律用 `val`。需要修改时用 `copy()` 创建新实例。

```kotlin
data class ConversationDecision(val action: ConversationAction, val reason: String? = null)
// ✅ val + data class，天然不可变
```

### 6. 接口隔离（ISP）

`LLM` 接口只暴露 `ask()`——不要加无关方法。`Tool` 只暴露 `execute()` + 描述字段。

### 7. 组合优于继承

Agent 的 `llm` 是组合（has-a），不是继承 LLM。Tool 的 `bot` 同理。不要 `class MyAgent : OpenAIAPI()`。

### 8. 模板方法模式

`Agent<I,O>` 的 `process()` 是模板方法：`fromJson → handle → toJson`。子类只实现 `handle()`，不改边界流程。`fromJson`/`toJson` 可 override 做自定义转换。

## 开发命令

```bash
./gradlew bootRun         # 运行机器人（没有 `run` 任务；需要 LLM_API_KEY 或兼容的 OpenAI 协议端点）
./gradlew test             # 运行所有测试
./gradlew build            # 构建（编译 + 测试 + jar）
./gradlew compileKotlin    # 仅编译，不跑测试

cd web && npm run build    # 构建控制台前端 → src/main/resources/static（产物已入库，没 Node 也能跑）
cd web && npm run dev      # 前端开发服务器 5173，/api 代理到 8080（改完记得 npm run build）
```

Web 控制台：`http://localhost:8080/`（token 用量 + **调用轨迹**（每次 LLM 调用的真实请求/回复/耗时，落 `token_usage` 表）+ 机器人状态，只读 API 见 `执行流程.md` 的「Web 控制台」一节）。

## 核心依赖

| 库 | 用途 |
|----|------|
| `com.mikuac:shiro:2.5.3` | OneBot 协议（QQ 机器人框架） |
| `dev.langchain4j:langchain4j` + `langchain4j-open-ai`（1.22.0 裸依赖，不用 starter） | OpenAI 兼容 LLM 客户端（1.x 才解析 prompt 缓存 `cached_tokens`；starter 是 Boot3 一代且其自动配置本项目从未使用） |
| `io.github.classgraph:classgraph` | 运行时 Agent/Tool 类自动发现 |
| `com.fasterxml.jackson` | JSON↔OOP 边界序列化（`jackson-module-kotlin:2.20.0` 显式依赖；Shiro 自身走 Jackson 3/`tools.jackson`，见「容易搞错的地方」） |
| `com.mysql:mysql-connector-j` | MySQL 驱动（`storage.type=mysql` 默认后端；代码只用 java.sql 标准接口） |

## Shiro 框架参考

遇到 Shiro 相关问题（配置、API、兼容性）时查阅：

- **GitHub 源码**：https://github.com/MisakaTAT/Shiro
- **官方文档**：https://misakatat.github.io/shiro-docs/

## 容易搞错的地方

- **Tool 发现是自动的**——不要手动注册。ClassGraph 扫描 classpath（Agent 已不再自动发现，主流程两个智能体在 `LoopConfig` 显式构造）。Tool 元数据（`description` = 两行「功能：…/何时使用：…」、`parameterHint` = 参数）会进 `list_tools` 目录，新 Tool 按此格式写好描述即对智能体可见。
- **JSON 只在边界**——Agent `handle()` 方法里禁止拼 JSON，用 OOP 对象。
- **`LLM.DEFAULT` 是懒加载单例**（不是 Spring bean）——绕过了 DI。
- **Gradle 使用国内镜像**——`gradle-wrapper.properties` 指向 `mirrors.aliyun.com`。
- **Spring Boot 4.0.3 + Kotlin 2.2.21**——版本很新，不要假设 Spring Boot 3.x 写法。
- **Shiro API**：`ArrayMsg.type` 是 `MsgTypeEnum` 枚举；`sendMsg(event, msg, autoEscape)` 签名。
- **发送回执的 `data` 是 `MsgId` 对象**（`ActionData<MsgId>`，字段 `messageId: Integer`）——`resp.data as? Number` 恒为 null；写入档前必须 `(resp.data as? MsgId)?.messageId?.toLong()`。
- **类型化爬楼 DTO 拿不到 message_id**（`GetMsgListResp → MsgResp → MessageEvent` 没有该字段）——需要 id 时走 `Bot.customRequest(ActionPath, params, Class<T>)` + 自定义 DTO（见 `bot/tool/OneBotHistoryDto.kt`），参数键与 Shiro 内部一致（`group_id`/`user_id`/`count`/`reverseOrder`）。
- **Shiro 2.5.x 走 Jackson 3**：`JsonUtils`/`customRequest` 的 JsonNode 类型是 `tools.jackson.databind.JsonNode`（Spring Boot 4 同代），但**注解包仍是 `com.fasterxml.jackson.annotation`**（两代通用）——自定义 DTO 的 JsonNode 字段必须用 `tools.jackson` 类型，注解不用换。
- **Shiro 2.5.0 + Spring Boot 4 / Tomcat 11 兼容性**：Tomcat 11 在 WebSocket 升级后回收 request，导致 `ConnectionUtils.parseSelfId` 读 `getHandshakeHeaders()` 崩溃。已通过 `CachedHandshakeSessionDecorator` 缓存 handshake headers 修复（见 `adapter/onebot/config/ShiroWebSocketConfig.kt`）。
- **Shiro WS 端点**：默认在 Spring Boot Tomcat 主端口（8080）的 `/ws/shiro` 路径，不是独立端口。
- **NapCat 连接地址**：`ws://localhost:8080/ws/shiro?self_id=机器人QQ号`

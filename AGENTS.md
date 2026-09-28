# AGENTS.md

## 项目概览

Kotlin Spring Boot 4.0.3 应用——基于 OneBot 协议（QQ）的模拟真人聊天机器人，使用 Shiro 框架 + LangChain4j 的 LLM 智能体系统。

## 架构

### 智能体系统（核心设计）

机器人按**固定脚本的主循环**驱动，**核心设计原则：模拟真人在 QQ 中的真实行为**：

- `Agent<I, O>`（泛型抽象类）——基类；每个 Agent 声明自己的输入/输出 OOP 类型，实现 `handle(input: I): O`。
- 主流程只有两个智能体：`SessionPickerAgent`（消息列表选会话）与 `ConversationAgent`（会话内决策），由 `MainLoopController` / `MessageListController` / `ConversationController` 按固定脚本调用。
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

### Tool 与 Agent 的区别

- **Agent**：LLM 驱动的智能处理单元，可以调用 LLM 做判断和生成（如 `ConversationAgent`）
- **Tool**：确定性的数据查询/交互操作，不调用 LLM（如 ChatHistoryTool）
- Tool 仍通过 ClassGraph 自动发现（`AgentDiscovery`），由 `ToolDependencyBinder` 绑定 Bot 依赖；主循环经 `processObject` 调用 Tool

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

默认：MiMo 端点（OpenAI 兼容）。通过 `application.yaml` 的 `llm.mimo.*` 配置：

```yaml
llm:
  mimo:
    base-url: "https://token-plan-cn.xiaomimimo.com/v1"
    api-key: ${LLM_API_KEY:mimo}   # 走 LLM_API_KEY 环境变量
    model-name: "mimo-v2.6-flash"
```

使用自研 `OpenAIAPI`（`bot/llm/`）——任何兼容 OpenAI 协议的端点均可。

## OOP 设计原则（必须遵守）

### 1. JSON 仅在 LLM API 边界

Agent/Tool 内部**一律使用 OOP 对象**（data class），不在业务逻辑里拼 JSON 字符串。JSON 序列化/反序列化只发生在 `Agent.process()` 模板方法的边界处。

```kotlin
// ✅ 正确：handle() 用 OOP 对象
override fun handle(input: ConversationRequest): ConversationDecision {
    val llmOutput = llm.ask(systemPrompt, "请按输出格式返回决策。")
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
./gradlew run              # 运行机器人（需要 LLM_API_KEY 或兼容的 OpenAI 协议端点）
./gradlew test             # 运行所有测试
./gradlew build            # 构建（编译 + 测试 + jar）
./gradlew compileKotlin    # 仅编译，不跑测试
```

## 核心依赖

| 库 | 用途 |
|----|------|
| `com.mikuac:shiro:2.5.0` | OneBot 协议（QQ 机器人框架） |
| `dev.langchain4j:langchain4j-open-ai-spring-boot-starter` | OpenAI 兼容 LLM 客户端 |
| `io.github.classgraph:classgraph` | 运行时 Agent/Tool 类自动发现 |
| `com.fasterxml.jackson` | JSON↔OOP 边界序列化（Shiro 传递依赖） |

## Shiro 框架参考

遇到 Shiro 相关问题（配置、API、兼容性）时查阅：

- **GitHub 源码**：https://github.com/MisakaTAT/Shiro
- **官方文档**：https://misakatat.github.io/shiro-docs/

## 容易搞错的地方

- **Tool 发现是自动的**——不要手动注册。ClassGraph 扫描 classpath（Agent 已不再自动发现，主流程两个智能体在 `LoopConfig` 显式构造）。
- **JSON 只在边界**——Agent `handle()` 方法里禁止拼 JSON，用 OOP 对象。
- **`LLM.DEFAULT` 是懒加载单例**（不是 Spring bean）——绕过了 DI。
- **Gradle 使用国内镜像**——`gradle-wrapper.properties` 指向 `mirrors.aliyun.com`。
- **Spring Boot 4.0.3 + Kotlin 2.2.21**——版本很新，不要假设 Spring Boot 3.x 写法。
- **Shiro API**：`ArrayMsg.type` 是 `MsgTypeEnum` 枚举；`sendMsg(event, msg, autoEscape)` 签名。
- **Shiro 2.5.0 + Spring Boot 4 / Tomcat 11 兼容性**：Tomcat 11 在 WebSocket 升级后回收 request，导致 `ConnectionUtils.parseSelfId` 读 `getHandshakeHeaders()` 崩溃。已通过 `CachedHandshakeSessionDecorator` 缓存 handshake headers 修复（见 `adapter/onebot/config/ShiroWebSocketConfig.kt`）。
- **Shiro WS 端点**：默认在 Spring Boot Tomcat 主端口（8080）的 `/ws/shiro` 路径，不是独立端口。
- **NapCat 连接地址**：`ws://localhost:8080/ws/shiro?self_id=机器人QQ号`

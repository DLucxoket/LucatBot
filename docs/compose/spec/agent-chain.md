---
feature: agent-chain
status: in-progress
updated: 2026-09-26
branch: feature/agent-chain
commits: 
---

# Agent Chain — 消息主链路与调度执行引擎

## Report

## [S1] Problem

机器人目前只有骨架：消息进来后无人处理，DispatcherAgent 只返回 LLM 的调用计划文本但无法执行，Session 为空。需要打通「消息接收 → 模型转换 → 调度 → Agent 并行执行 → 结果汇总 → 循环决策 → 回复」的完整链路，让机器人能真正对话。

## [S2] Design

### 2.1 主链路数据流

```
Shiro AnyMessageEvent
  → ShiroConverter 转为 model 层对象
  → MainPlugin 调用 AgentChain
  → AgentChain 循环调度:
      DispatcherAgent.process(上下文)
        → 解析输出为执行计划 [{agentName, input}, ...]
        → 并行执行所有 Agent
        → 收集结果，构建汇总上下文
        → 若输出 "done" 则终止
        → 否则继续下一轮调度
  → 最终结果回复给用户
```

### 2.2 DispatcherAgent 输出格式（调用计划）

每轮调度 LLM 输出格式：
```
Agent1: 给Agent1的输入
Agent2: 给Agent2的输入
```
或 `done` 表示终止。

**关键设计约束**：
- 每轮所有 Agent **并行执行**，互不等待
- 每个 Agent 的输入由 DispatcherAgent **显式指定**，不塞全量上下文
- 全部 Agent 返回后，结果汇总为结构化文本送回 DispatcherAgent 做下一轮决策
- 循环直到 DispatcherAgent 输出 `done`

### 2.3 新增接口与类

#### `AgentPlan`（数据类）
```kotlin
data class AgentPlan(
    val agentName: String,
    val input: String
)
```
表示一个待执行的 Agent 调用计划。

#### `AgentExecutionResult`（数据类）
```kotlin
data class AgentExecutionResult(
    val agentName: String,
    val input: String,
    val output: String,
    val success: Boolean,
    val error: String? = null
)
```

#### `AgentChain`（核心执行引擎）
```kotlin
class AgentChain(private val dispatcher: DispatcherAgent) {
    fun execute(initialInput: String): String
}
```
- 解析 DispatcherAgent 输出（`AgentPlan` 列表或 `done`）
- 并行执行所有 Plan（`kotlinx.coroutines` 或 `CompletableFuture`）
- 收集结果构建下一轮上下文：`"[AgentName] 输入 → 输出"` 格式
- 循环直到 `done` 或超过最大轮次限制（默认 5 轮）
- 返回最终汇总文本

#### `AgentPlanParser`（工具类）
```kotlin
object AgentPlanParser {
    fun parse(llmOutput: String): List<AgentPlan>?  // null 表示 done
}
```
解析 `"AgentName: input"` 格式，忽略空行，`done` 返回 null。

#### `ShiroConverter`（转换工具）
```kotlin
object ShiroConverter {
    fun toModelEvent(event: AnyMessageEvent): MessageEvent
    fun toModelMessage(message: AnyMessage): ChatMessage
}
```
Shiro DTO → model 层对象的映射。

### 2.4 模型层补全

#### `MessageEvent` 补充字段
```kotlin
abstract class MessageEvent(
    val message: Message,
    val sender: User,       // 新增：发送者
    val timestamp: Long     // 新增：时间戳
)
```

#### `GroupMessageEvent` / `PrivateMessageEvent` 补充字段
```kotlin
class GroupMessageEvent(message: Message, sender: User, val group: Group, timestamp: Long)
class PrivateMessageEvent(message: Message, sender: User, timestamp: Long)
```

### 2.5 示例 Agent

#### `EchoAgent`
- 回显输入，验证链路连通性
- description: "将输入原样返回，用于测试"

#### `ReplyAgent`
- 调用 LLM 根据上下文生成智能回复
- description: "根据上下文生成自然的对话回复"

### 2.6 MainPlugin 集成

```
onAnyMessage:
  1. ShiroConverter.toModelEvent(event)
  2. 提取纯文本内容作为 initialInput
  3. AgentChain.execute(initialInput)
  4. bot.sendMsg(result)
  5. return MESSAGE_BLOCK（阻断后续插件）
```

### 2.7 错误处理

- Agent 执行异常：捕获后记入 `AgentExecutionResult.error`，不中断链路
- DispatcherAgent 输出格式异常：记日志，返回错误消息给用户
- 超过最大轮次：强制终止并返回已有结果汇总

### 2.8 Tool 包设计（与 agent 包平行）

仿照 `bot/agent/` 的结构，新建 `bot/tool/` 包，用于与 onebot、model 层交互（查聊天记录、看用户主页、查消息列表等）。

#### `Tool`（抽象基类）
```kotlin
abstract class Tool {
    abstract val name: String          // 工具名称（唯一标识）
    abstract val description: String   // LLM 可读的功能描述
    abstract val parameterHint: String // 参数格式说明（供 LLM 理解）
    abstract fun execute(input: String): String
}
```
与 Agent 的区别：Agent 是 LLM 驱动的智能处理单元，Tool 是确定性的数据查询/交互操作。Agent 可调用 Tool 获取数据，Tool 不调用 LLM。

#### 示例 Tool
| 类名 | 功能 | 输入 | 输出 |
|------|------|------|------|
| `ChatHistoryTool` | 查询聊天记录 | 群号/用户号 + 条数 | 最近 N 条消息文本 |
| `UserProfileTool` | 查看用户主页 | QQ 号 | 用户资料（昵称、签名等） |
| `MessageListTool` | 查看消息列表 | 会话标识 | 消息摘要列表 |

#### Tool 发现机制
与 Agent 相同：ClassGraph 自动扫描 `Tool` 子类，无需手动注册。DispatcherAgent 可将 Tool 纳入调度候选。

## [S3] Out of Scope

- Session / 多轮对话上下文持久化（下轮实现）
- 真实业务 Agent（查天气、搜索等）
- Bot 接口的具体实现类
- 消息图片/表情的完整解析（仅处理文本和 @ 片段）
- WebSocket 连接管理

## Tasks

- [ ] T1: 实现 `AgentPlan` / `AgentExecutionResult` 数据类 — acceptance: 类可编译，字段完整 (covers: S2.3)
- [ ] T2: 实现 `AgentPlanParser` 解析器 — acceptance: 正确解析 `AgentName: input` 多行格式；`done` 返回 null；空行被忽略 (covers: S2.3)
- [ ] T3: 补全 model 层字段（MessageEvent 加 sender/timestamp，Group/Private 加 group/sender） — acceptance: 所有 model 类可编译，字段完整 (covers: S2.4)
- [ ] T4: 实现 `ShiroConverter` — acceptance: AnyMessageEvent 的文本片段正确转为 TextMessageMember，事件类型正确 (covers: S2.3; depends: T3)
- [ ] T5: 实现 `AgentChain` 执行引擎 — acceptance: 能解析计划、并行执行、收集结果、循环调度、终止 (covers: S2.2, S2.3, S2.7; depends: T1, T2)
- [ ] T6: 实现 `EchoAgent` 示例 — acceptance: 继承 Agent，process 返回输入内容 (covers: S2.5)
- [ ] T7: 实现 `ReplyAgent` 示例 — acceptance: 调用 LLM 生成回复，继承 Agent (covers: S2.5)
- [ ] T8: 集成 `MainPlugin.onAnyMessage` — acceptance: 消息进来后走完整链路并回复 (covers: S2.6; depends: T4, T5, T6)
- [ ] T9: 编译验证 + 更新 DESIGN.md/AGENTS.md — acceptance: `./gradlew compileKotlin` 通过，文档反映新增类 (covers: S2; depends: T8)
- [ ] T10: 实现 `Tool` 抽象基类 — acceptance: 抽象类可编译，包含 name/description/parameterHint/execute (covers: S2.8)
- [ ] T11: 实现 3 个示例 Tool（ChatHistoryTool/UserProfileTool/MessageListTool） — acceptance: 继承 Tool，实现 execute，能通过 OneBot API 查询数据 (covers: S2.8; depends: T10)
- [ ] T12: DispatcherAgent 集成 Tool 发现 — acceptance: Tool 类出现在调度器的可用工具列表中 (covers: S2.8; depends: T10, T11)

# OOP 审查报告

> 依 AGENTS.md 八原则对各阶段改动做审查。本文件为持久存档，修复进度以 git 提交记录为准。

## 第三轮（近六批改动）

范围：展示名/身份、消息列表待办、发送失败、顶部提示、selfQq 判定、记忆判重。

### P1 — 架构约定违例 / 复制粘贴

| # | 原则 | 问题 | 证据 | 修复方向 |
|---|------|------|------|----------|
| ① | DIP + 分层 | 核心层直接消费 Shiro 具体类型：`ConversationController` 调 `lucatBot.shiroBot?.selfId` / `?.getGroupInfo(...)`。`ShiroConverter` KDoc 声明「核心逻辑不依赖 Shiro 框架类」；根因是 `LucatBot.shiroBot` 泄漏，本轮新增了两处核心层消费点 | `ConversationController.kt:129,502` | 窄接口 `BotGateway`（`selfId` / `groupNameOf`），adapter 实现、注入 controller |
| ② | DRY | 会话身份行格式双实现：`ConversationController.buildIdentity` vs `MainPlugin.handlePoke` 手拼「私聊：网友 QQ …」，已有不一致 | `ConversationController.kt:486` / `MainPlugin.kt:142` | 共享 `ConversationIdentity` 组装器，两处调用 |
| ③ | DRY | todo 副作用处理整段复制：`MessageListController.applyTodoSideEffects` 与 `ConversationController` 的 todo 分支逐行相同 | `MessageListController.kt:121-144` | 提取 `TodoSideEffectExecutor`（组合 TodoService+ActionLog），两 controller 组合使用 |
| ④ | 整洁 | 本轮引入的格式缺陷：KDoc 与函数声明被挤到同一行（edit 吃换行） | `MessageListController.kt:146`、`ConversationController.kt:469` | 直接修复 |

### P2 — SRP / 可读性

| # | 原则 | 问题 | 证据 |
|---|------|------|------|
| ⑤ | SRP | `ConversationController` 522 行、12 个方法，职责堆积（编排+身份+hint+副作用+失败文案+格式化）；身份部分可随 ② 拆出 | `ConversationController.kt` |
| ⑥ | 可读性 | 隐藏出参：`applyDecisionSideEffects(...): String?` 返回「延后的 remember」，副作用函数带隐式返回值，调用点须知潜规则 | `ConversationController.kt:382,206` |
| ⑦ | DRY | 展示名规则双实现：`User.displayName`（实时）vs `ChatHistoryTool` 内联 `card ?: nickname`（爬楼）——规则改动需两处同步 | `User.kt:33` / `ChatHistoryTool.kt:116-117` |
| ⑧ | 可读性 | 裸 Int 四连 `getMemorySummary(subject, 50, 30, 50, 20)` | `MemoryReflector.kt:68`（建议不动：收益小于改动风险） |

### 通过项（合规确认）

- **JSON 仅在边界** ✓：判重/副作用/身份组装全程 OOP，无业务拼串
- **不可变优先** ✓：新增字段全 `val` data class；`loop` 内 `var` 属编排局部状态
- **OCP** ✓：`HistoryMessageItem`/`ConversationRequest`/`SessionDecision` 加字段带默认值零破坏；判重收口 `MemoryService` 单点
- **ISP** ✓：未膨胀任何接口；`BotGateway`（①的修复方向）本身即 ISP 应用
- **组合优于继承** ✓：reflector / ToolNotes / 副作用全组合
- **模板方法** ✓：`Agent.process` 未被触碰
- **错误契约** ✓：send 失败走 `success=false`，`ToolErrorContractTest` 守卫

### 已知不改（既有问题，非本轮引入）

- `MemoryService.getMemorySummary` 承担 prompt 渲染（拼 markdown）——SRP 张力，动它影响面大。

### 修复计划（顺序）

1. `refactor` ④ 格式修复
2. `refactor` ① `BotGateway` 窄接口收口 Shiro 依赖
3. `refactor` ② `ConversationIdentity` + ③ `TodoSideEffectExecutor`
4. `refactor` ⑤⑥⑦ P2 清理

> 优先级调整：chat_history 存储层双份行问题（见截图取证）优先于以上全部——先修 `fix(chathistory)`，本报告修复计划顺延。

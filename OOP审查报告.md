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

---

## 第四轮（147deef..HEAD）

> **范围**：第三轮存档后 17 个提交、45 文件、+2978/-178（图片通道、记忆写入日期、身份展示名、prompt 措辞等）。
> **方式**：四分区（llm/model、chathistory/storage、loop/agent、memory/tool/test）并行静态审查；bug 级行由父级逐条实证复核；全量测试 279 用例绿作基线。
> **汇总**：P1 8 项（去重后；前 3 项为实证 bug）/ P2 25 项（去重后）。

### 第三轮修复计划状态

| 项 | 状态 |
|----|------|
| 存储层双份行（当时最高优先级） | ✅ 已修（`bbb7478` msg: 稳定 id + 孪生折叠 + 启动清理） |
| ④ KDoc 与声明挤同一行 | ✅ 已修（两处分行，全源码 grep 无残留） |
| ① 核心层消费 Shiro 类型（BotGateway） | ⚠️ **加重**——图片刷新链新增 `lucatBot.shiroBot?.getMsg` |
| ② 会话身份行双实现 / ③ todo 副作用整段复制 | ❌ 原样（③ 签名已分叉：add(content) vs add(content, convId)） |
| ⑤ ConversationController 膨胀 | ⚠️ **加重** 522→654 行、12→15 方法 |
| ⑥ applyDecisionSideEffects 隐式返回 | △ 缓解（KDoc @return + 调用点命名 deferredRemember，形态未变） |

### P1 — 架构约定违例（前 3 项为实证 bug，按优先级排序）

| # | 原则 | 问题 | 证据 | 修复方向 |
|---|------|------|------|----------|
| 1 | DIP/委托转发完整性 **【实证 bug】** | **生产图片通道整体失效**：`LLM.DEFAULT` 匿名委托只 override 两参，三参 `ask(s,q,images)` 走接口默认体**先丢 images** 再转两参；生产 `LoopConfig:93 → ConversationAgent()` 默认注入 DEFAULT，`OpenAIAPI` 的多模态 override（:142）永远走不到——预取/救援/刷新全部取材在最后一步断链。测试全绿因 FakeLLM 直接 override 三参绕过 DEFAULT、ImageChannelTest 直测 OpenAIAPI，盲区成立 | LLM.kt:52-55、:76-77；ConversationAgent.kt:17、:31；LoopConfig.kt:93；OpenAIAPI.kt:142 | DEFAULT 补三参 override 转发；补 `LLM.install(fake)+三参调用` 转发测试 |
| 2 | 设计一致性 **【实证】** | **负缓存与救援链互斥**：`resolve` 的 `rejected` 检查（:71）在刷新逻辑之前短路——预取不带 refresh，4xx 即写 `rejected[key]`（:109），后续带 refresh 的取材 :71 直接返回，`get_msg` 救援链一次不执行；refresh 瞬时失败也永久拉黑（无 TTL），与「瞬时故障下轮重试」「图片必达」承诺矛盾；ImageChannelTest:180-196 把该行为固化为预期 | ImageResolver.kt:71、:109、:114-133；ImagePrefetcher.kt:46；ConversationController.kt:501-510 | 负缓存只答「URL 必败」不短路 ref 维度救援（命中时该 ref 未救援过仍试一次），或 rejected 加短 TTL |
| 3 | 契约（失败即 null）**【实证】** | **刷新路径异常逃逸**：内层 `download(fresh)` 只 catch `HttpStatusException`（:125），IO/超时异常从 catch 块逃逸（兄弟 catch 接不住），经 `resolve`(:75 无保护) → `gatherPromptImages`(无 runCatching) 冒泡到决策轮——中断本轮并**跳过退出记忆整理**，违反「图片通道绝不让会话链路失败」两处承诺 | ImageResolver.kt:121-133、:100-138；ConversationController.kt:196-201 | 内层重试整体 runCatching 统一「拿不到即 null」；gatherPromptImages 对 resolve 兜底 |
| 4 | DRY（项目明文约定） | **展示名规则双实现且已漂移**：`User.displayName` KDoc 明文「实时入档与爬楼入档必须都用它」，爬楼侧在 ChatHistoryTool 内联重写 `card ?: qqNickname ?: bot/senderId`；空白昵称实时侧存 `""`、爬楼侧存 senderId，SenderDisplayNameTest 只测实时路径无共享护栏（第三轮遗留⑦，本区间随 CrawlMsg 改写仍未收敛） | User.kt:25-33；ChatHistoryTool.kt:124-130；ShiroConverter.kt:124-138 | 抽共享实现（爬楼侧构造 User 复用 displayName），补爬楼侧对称用例 |
| 5 | DRY / 复制粘贴 | **`msg:` id 规则三处复制、生产与解析不同源**：`"msg:$会话:$id"` 在三个入档口各写一遍、UUID 兜底各一份，前缀解析散落 ChatHistoryFold 四处——id 风格再变需改 7 处（`bbb7478` 全量改名即实证）；三个入档方法还是同一骨架样板 | ChatHistoryService.kt:34、:62、:100-101、骨架 :32-52/:60-74/:88-114；ChatHistoryFold.kt:44-48、:55-64 | 抽 `RecordId.msg/hist` 单点构造 + 前缀常量；三路径收敛到私有 `saveRecord(...)` |
| 6 | SRP / 层级违例 | **孪生收敛编排双份 + 迁移逻辑写进 @Configuration**：库内 `collapseStoredTwins()` 与迁移源 `collapseSourceJsonTwins()` 各写一遍「groupBy→fold→收敛」；后者自建 ObjectMapper、硬编码 `"data/chat-history.json"`（第 3 份拷贝）、直接改写文件——配置类已兼 Bean 装配 + 启动清理编排 + 文件改写三职 | ChatHistoryService.kt:140-166；StorageConfig.kt:90-97、:99-115；路径既有 JsonFileChatHistoryRepository.kt:18、MySqlStorage.kt:401 | 抽 `ChatHistoryTwinMigrator` 统一编排，StorageConfig 只留 `@Bean`；路径提共享常量 |
| 7 | OCP / 复制粘贴 | **图片成员提取规则三处复制**：Image/Meme→url+trim+base64/空白过滤在 `ChatMessage.assetUrls` 与 `ImagePrefetcher.targetsOf` 各一份（含两段近同 when 分派），ImageResolver 再独立查 base64；「URL 末段作文件名」兜底 4 处——漏改即预取与取材静默分叉 | ChatMessage.kt:121-128、:141；ImagePrefetcher.kt:62-77；ImageResolver.kt:70、:86；ConversationController.kt:482 | model 层单一出口 `ChatMessage.assets(): List<AssetRef>`，各处委托 |
| 8 | 设计不变量 **【疑似】** | **refs↔urls 下标对齐易破**：`assetUrls` 过滤 base64/空成员而 `describe` 仍渲染占位符；用户敲的 `[图片 file=…]` 文本也凭空多出 ref——任一发生即下标错位，附件贴错标签或误报 missing | AssetMarkers.kt:10-11、:24-28；ChatMessage.kt:127 vs :68、:81-83；ConversationController.kt:480-488 | 入档/describe 直接产出 (占位符,url) 配对对象，废除下标配对 |

### P2 — SRP / 可读性（25 项，按区域分组）

**A. LLM 图片通道（9）**

| # | 原则 | 问题 | 证据 |
|---|------|------|------|
| A1 | 错误语义 | 三参 ask `catch(Exception)` 全量降级纯文本：超时/429/鉴权错误也被吞、多发一次纯文本（翻倍延迟），调用方无从得知图被丢 | OpenAIAPI.kt:144-156 |
| A2 | DRY | 两参/三参 ask 各自复制 system 分支与 ChatRequest 组装 | OpenAIAPI.kt:113-125 vs :146-152 |
| A3 | DIP | ConversationController 构造默认 `ImageResolver()` 与 LoopConfig「必须同一实例否则白下」矛盾——未显式注入即第二缓存域、无编译期提示（分区三#6 合并） | ConversationController.kt:57-58 vs LoopConfig.kt:33-40 |
| A4 | 线程设计 | `resolve` 整体 @Synchronized 串行所有地址：2 线程预取池实际并发 1，锁内含网络下载+刷新回调，决策轮可能被无关图片阻塞数秒 | ImageResolver.kt:67-75、:100-138 |
| A5 | 魔法数 | 体积闸双源：实例 maxBytes 可配但 httpGet 硬编码 4MB；超限仍先整包下载再丢弃 | ImageResolver.kt:37、:77、:215 |
| A6 | 类型语义 | 本地文件缺失用 `HttpStatusException(404)` 表达；刷新路径把本地路径写进 rejected 键 | ImageResolver.kt:143-146、:125-128 |
| A7 | 日志安全 | PromptImage 默认 toString/equals 带整块 base64，打印即倾倒 MB 级文本，`distinct` 做巨型串比较 | PromptImage.kt:14-19；ConversationController.kt:495 |
| A8 | 健壮性【疑似】 | SendContentParser 对无界数字组直接 toLong/toInt，LLM 输出超长数字时 parse 抛 NumberFormatException，send 走异常而非受控失败 | SendContentParser.kt:87-91 |
| A9 | DRY | imageUrlOf 自建 `[CQ:image,…]` 正则，与类内 cqCodeRegex 重复定义 CQ 语法 | CqParser.kt:112 vs :23 |

**B. 编排层（5）**

| # | 原则 | 问题 | 证据 |
|---|------|------|------|
| B1 | SRP/⑤膨胀 | 图片取材管线整体长在控制器（ImageLine/PromptAttachments 内嵌类 + gatherPromptImages + refreshSupplier 约 76 行），与 bot/llm 的 Resolver/Prefetcher 分居两层 | ConversationController.kt:194-201、:455-530 |
| B2 | SRP | hint/失败提示/防复读文案继续堆进控制器（buildSendFailureNote + buildHint + quoteForHint 约 68 行） | ConversationController.kt:537-605 |
| B3 | 可读性 | 主链路大量全限定类名内联代替 import（PromptImage×3、AssetMarkers、CqParser、ImagePrefetcher、ChatMessage） | ConversationController.kt:58、460、476、479、523；ConversationModels.kt:237；MainPlugin.kt:54；LucatBot.kt:238 |
| B4 | OCP【疑似】 | 禁言分类靠错误文案子串嗅探 `contains("1200")\|\|contains("禁言")`，依赖 SendMessageTool 文案格式不变，应从 SendMessageResult 结构化 retCode 判断 | ConversationController.kt:538（调用点 :294） |
| B5 | DRY（轻） | `lastBotSpeakTime(...)?.let{formatDuration(...)}` 同表达式两份；「（#id）」标记格式双实现（控制器新消息注释 vs 智能体历史行） | MessageListController.kt:167、184；ConversationController.kt:190 vs ConversationAgent.kt:144 |

**C. 存储/聊天记录（4）**

| # | 原则 | 问题 | 证据 |
|---|------|------|------|
| C1 | SRP | ChatHistoryService 职责堆积加重：282 行/12 方法，入档×3 + 读取折叠 + 启动清理/迁移 + prompt 渲染 + 打标×2 + 展示名 + 委托 + 类型检测（≥5 个变更原因），类头 KDoc 仍自称「存取与查询」 | ChatHistoryService.kt:21-282（:32/:60/:88/:140/:173/:199/:255） |
| C2 | 组件契约【疑似】 | `ChatHistoryFold.isTwin` 不校验 conversationId，会话隔离外置给调用方预分组——混合列表直接 fold 会跨会话合并；当前 3 调用点都先分组，属潜在陷阱 | ChatHistoryFold.kt:55-64 |
| C3 | DRY【疑似】 | 富占位符词表两处维护：Fold 归一化正则 vs Service.detectMessageType 各自枚举同族标记 | ChatHistoryFold.kt:31-34 vs ChatHistoryService.kt:255-268 |
| C4 | DRY/字符串契约【疑似】 | 「（已记忆）」字面量两处：Service 渲染进 prompt、MemoryAgent 指令向 LLM 解释语义，改名不同步则静默失配 | ChatHistoryService.kt:184 vs MemoryAgent.kt:70 |

**D. 记忆/工具（6）**

| # | 原则 | 问题 | 证据 |
|---|------|------|------|
| D1 | SRP | ChatHistoryTool 252 行一 object：查询呈现 + 双 transport 爬取 + DTO 映射 + JsonNode→CQ + 展示名 + 时间戳 + 截断 + 入档，因三类原因而变 | ChatHistoryTool.kt:42-69、:95-157、:168-251 |
| D2 | SRP/分层耦合 | MemoryService 继续扩张（检索+判重+分桶渲染+日期+跨主体全量一体），渲染层硬编码其它模块工具名 `MemoryQueryTool`（memory→tool 字符串耦合） | MemoryService.kt:166-193（:189）、:200-240 |
| D3 | DRY | renderBuckets 四个同构桶块；「（记于…）」行尾后缀复制 5 处（4 桶 + 概览 others 行） | MemoryService.kt:215-230、:190 |
| D4 | 文档一致 | KDoc 仍称「strength 仅作排序权重保留」，但本轮三处排序全改 createdAt 后已无读取方——注释被本区间改动改成假话 | MemoryService.kt:14-16 vs :130、:179、:258 |
| D5 | SRP/可诊断性 | OneBotImageDto.kt 混装 ActionPath + 响应 DTO + 业务动作 NapCatImageAction，文件名与内容不符；`catch{null}` 静默吞异常无日志 | OneBotImageDto.kt:13-27、:43-59 |
| D6 | 设计【疑似】 | getMemoryOverview 每次注入 prompt 都 `repository.findAll()` 全量加载再内存过滤 take，随记忆增长线性放大决策轮构建成本 | MemoryService.kt:177-180 |

**E. 测试结构（1）**

| # | 原则 | 问题 | 证据 |
|---|------|------|------|
| E1 | 测试结构/DRY | ConversationControllerTest 单文件 1210 行装 4 个替身 + 3 个测试类（替身应独立成文件）；buildController 已 10 参数；PNG 魔数字面量复制 4 处；MemoryDedupeTest 的 Fixture 夹在测试方法中间 | ConversationControllerTest.kt:41-104、:109-157、:272、302、327、:926、:1022；ImageChannelTest.kt:30；MemoryDedupeTest.kt:175 |

### 通过项（合规确认）

- **JSON 仅在边界**：Agent/Tool 的 handle 全程 OOP；OpenAIAPI 用 langchain4j Content OOP 组装序列化交给库；控制器无 JSON 拼接；prompt 内嵌输出格式 JSON 属指令层。
- **错误契约**：SendMessageTool 全部错误出口 success=false（含被禁言路径），成功才写档；ToolErrorContractTest 全量守卫 + 断言发现数 ≥27。
- **模板方法未破坏**：三个 Agent、Tool.process 的 fromJson→handle→toJson 原样。
- **不可变优先**：本区间新增字段全 `val`（ChatMessageRecord/PromptImage/MemoryItem/ConversationRequest.images/UnreadMessage 等），修改走 copy()。
- **DIP/可注入**：imageSource lambda、ImageResolver.fetchHttp、MemoryService 构造注入、Tool 的 bindBot/bindServices（@Volatile）、LoopConfig 显式装配；未新增硬编码单例。
- **OCP**：新增 DTO 非 Tool 子类不被 ClassGraph 误发现；富消息能力经 model 层解析器扩展。
- **Shiro 3 边界约定**：OneBotHistoryDto 的 JsonNode 用 `tools.jackson`、注解用 `com.fasterxml`，合规；发送回执已按 MsgId 对象取 id。
- **判重单点守住**：生产写入口全经 MemoryService.isDuplicate，无绕过。
- **测试替身 DRY**：4 个 Fake 各定义一次全包复用，无复制粘贴。
- **ChatHistoryFold 抽取正向**：纯 object 零依赖、规则单一来源，三处复用 + ChatHistoryTwinCollapseTest 覆盖。

### 已知不改（非本阶段引入 / 收益小于风险）

- `LLM.DEFAULT` 懒加载单例架构本身（AGENTS.md 已登记）——P1-1 只要求补三参转发，不主张改架构。
- MemoryItem.strength / RELATION 枚举 / relationLimit 桶：YAGNI 残留且涉 MySQL 列，本轮只修 KDoc。
- 边界 DTO 用 var：Shiro Jackson3 反序列化需 setter，原则 5 针对业务数据类。
- formatTime 双实现且日期格式不一致（ConversationController yyyy-MM-dd vs MessageListController MM-dd HH:mm）。
- OpenAIAPI 每次 ask 重建 model、短 AssetRef 阈值魔法数、缓存容量未外置 yaml、戳一戳旁路直接 `ConversationAgent()`、`todo_add` JSON 契约命名。

### 修复计划（顺序）

1. **`fix(llm)` P1-1**：DEFAULT 补三参转发 + 转发测试——几行改动，立即解锁整个图片通道（当前生产图片全断）。
2. **`fix(llm)` P1-2 + P1-3**：负缓存不短路救援 / refresh 失败不永久拉黑；异常逃逸 runCatching 收口——同属图片必达链路，可一批，ImageChannelTest 已有基础。
3. **`refactor`** 展示名收口共享实现（P1-4，兼第三轮遗留⑦）。
4. **`refactor`** RecordId 单点 + saveRecord 模板（P1-5）。
5. **`refactor`** ChatHistoryTwinMigrator 抽出、StorageConfig 瘦身（P1-6）。
6. **`refactor`** 图片资产单一出口（P1-7）+ 配对替代下标（P1-8 疑似）。
7. 第三轮遗留继续：① BotGateway（已加重）、③ TodoSideEffectExecutor、⑤ ConversationController 拆分（与 B1/B2 同批）。
8. P2 分批清理（A 组随 1-2 顺手修，E 组测试结构独立一批）。

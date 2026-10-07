# web/ —— LucatBot 控制台（Vue 3 + Vite）

可视化两组数据（全部只读 GET，**不触发任何 LLM 调用**）：

| 分组 | 端点 | 页面 |
|------|------|------|
| token 用量 | `/api/token/summary`、`/api/token/timeseries`、`/api/token/calls` | Token 用量页：总账卡片、**通道（文字/图片）**环图、**agent/会话/模型**维度条形图、趋势折线、调用明细表 |
| 机器人状态 | `/api/state/overview` | 机器人状态页：状态机当前态、心情、待办、记忆（总量/按类型/最近几条）、操作日志、未读与最近会话 |

## 开发

```bash
cd web
npm install          # 首次
npm run dev          # http://localhost:5173 ，/api 代理到 8080（见 vite.config.js）
```

改 `src/` 即热更新；看真实数据要让机器人跑起来（`./gradlew run`，端口 8080）。

## 构建

```bash
cd web
npm run build        # 产物直接落到 ../src/main/resources/static
```

产物**随 jar 打包**（`./gradlew build` 不需要 Node），启动后访问：

```
http://localhost:8080/          → 控制台
http://localhost:8080/api/token/summary
```

改了前端记得重新 `npm run build` 再重启/重打包；`static/` 是构建产物、**入库**（保证没装 Node 的机器也能开面板）。

## 结构

```
web/
├── vite.config.js          # base './'、产物进 resources/static、dev 代理 /api
├── index.html
└── src/
    ├── main.js / App.vue   # 挂载 + 顶部 Tab（Token 用量 / 机器人状态）+ 自动刷新
    ├── api.js              # 只读 fetch 封装 + 数字/时间格式化
    ├── style.css           # 暗色主题，无 UI 库
    └── components/
        ├── TokenPanel.vue  # 用量页（汇总/维度/趋势/明细）
        ├── StatePanel.vue  # 状态页
        ├── BarList.vue     # 横向条形图（维度对比）
        ├── DonutChart.vue  # 环图（通道占比）
        ├── TrendChart.vue  # SVG 折线+面积（时间轴）
        └── StatCard.vue    # 指标卡
```

图表全部是手写 SVG/CSS（无图表库），依赖只有 `vue` + `vite` + `@vitejs/plugin-vue`。

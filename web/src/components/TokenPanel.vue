<script setup>
import { ref, watch, onMounted, computed } from 'vue'
import { fetchSummary, fetchTimeseries, fetchTimeseriesGrouped, fetchCalls, fmt, compact, timeText, actionLabel } from '../api.js'
import StatCard from './StatCard.vue'
import BarList from './BarList.vue'
import DonutChart from './DonutChart.vue'
import TrendChart from './TrendChart.vue'

const props = defineProps({ tick: { type: Number, default: 0 } })

const summary = ref(null)
const series = ref([])
const calls = ref([])
const error = ref('')
const loading = ref(false)

const bucket = ref('day')
const metric = ref('total')
const dim = ref('byAgent')
const limit = ref(100)

/** 趋势图分类（分线）：none = 单线总量；其余按维度拆成多条线 */
const group = ref('none')
const grouped = ref([])
const MAX_TREND_LINES = 6
const groups = [
  { key: 'none', label: '整体' },
  { key: 'action', label: '动作' },
  { key: 'conversation', label: '会话' },
  { key: 'agent', label: 'agent' },
  { key: 'model', label: '模型' },
  { key: 'channel', label: '通道' }
]

const dims = [
  { key: 'byAgent', label: '按 agent' },
  { key: 'byConversation', label: '按会话' },
  { key: 'byAction', label: '按动作' },
  { key: 'byModel', label: '按模型' }
]
const metrics = [
  { key: 'total', label: '合计' },
  { key: 'input', label: '入参' },
  { key: 'output', label: '出参' },
  { key: 'cost', label: '消耗' }
]

// 「按动作」维度汉化展示（记账仍存 wire 名，见 api.js 的 actionLabel）
const rows = computed(() =>
  summary.value
    ? summary.value[dim.value].map((r) => (dim.value === 'byAction' ? { ...r, name: actionLabel(r.name) } : r))
    : []
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [s, t, c, g] = await Promise.all([
      fetchSummary(),
      fetchTimeseries(bucket.value, 30),
      fetchCalls(limit.value),
      group.value === 'none'
        ? Promise.resolve([])
        : fetchTimeseriesGrouped(group.value, bucket.value, 30)
    ])
    summary.value = s
    series.value = t
    calls.value = c
    grouped.value = g
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

/**
 * 分类趋势 → TrendChart 的分线数据：按 key 拆线、按 bucket 对齐（缺桶补 0）
 *
 * 只画**区间合计前 6** 的线（会话/动作天然多类，画全读不了），被截断的
 * 类数经 extraKeys 在图例尾部点名。动作分类的线名走 actionLabel 汉化。
 */
const trendSeries = computed(() => {
  if (group.value === 'none' || !grouped.value.length) return []
  const buckets = [...new Set(grouped.value.map((r) => r.bucket))].sort()
  const byKey = new Map()
  for (const r of grouped.value) {
    if (!byKey.has(r.key)) byKey.set(r.key, new Map())
    byKey.get(r.key).set(r.bucket, r)
  }
  const ranked = [...byKey.entries()]
    .map(([key, m]) => ({ key, m, sum: [...m.values()].reduce((a, r) => a + r.total, 0) }))
    .sort((a, b) => b.sum - a.sum)
  const zero = (b) => ({ bucket: b, calls: 0, input: 0, output: 0, total: 0 })
  return ranked.slice(0, MAX_TREND_LINES).map(({ key, m }) => ({
    name: group.value === 'action' ? actionLabel(key) : key,
    points: buckets.map((b) => m.get(b) || zero(b))
  }))
})

/** 没画出来的分类数（图例尾部提示） */
const extraKeys = computed(() => {
  if (group.value === 'none' || !grouped.value.length) return 0
  return Math.max(0, new Set(grouped.value.map((r) => r.key)).size - MAX_TREND_LINES)
})

watch(() => props.tick, load)
watch(bucket, load)
watch(limit, load)
watch(group, load) // 换分类要重新拉 timeseries-grouped
onMounted(load)
</script>

<template>
  <div class="grid" style="gap:16px">
    <div v-if="error" class="card error">加载失败：{{ error }}（机器人是否在 8080 运行？）</div>
    <div v-if="loading && !summary" class="card muted">加载中…</div>

    <!-- 总账 -->
    <div class="grid cards" v-if="summary">
      <StatCard
        label="调用次数"
        :value="fmt(summary.total.calls)"
        :sub="`进场首轮 ${fmt(summary.firstRoundCalls)} 次 · 端点未返回用量 ${summary.noUsageCalls} 次`"
      />
      <StatCard
        label="入参 token（含图片）"
        :value="compact(summary.total.input)"
        :sub="`${fmt(summary.total.input)} · 缓存命中 ${fmt(summary.cachedInput)}` +
          (summary.cachedRate != null ? `（命中率 ${(summary.cachedRate * 100).toFixed(2)}%）` : '')"
      />
      <StatCard label="出参 token" :value="compact(summary.total.output)" :sub="fmt(summary.total.output)" />
      <StatCard
        label="合计 token"
        :value="compact(summary.total.total)"
        :sub="`平均单次 ${fmt(Math.round(summary.total.total / Math.max(1, summary.total.calls)))} token · 跨重启累计（token_usage 表）`"
      />
      <StatCard
        v-if="summary.estimatedCost != null"
        label="估算消耗（≈）"
        :value="summary.estimatedCost.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) + (summary.costUnit ? ' ' + summary.costUnit : '')"
        sub="按模型三档计费（命中缓存走缓存档）· 仅计已配价模型"
      />
    </div>

    <div class="grid two" v-if="summary">
      <!-- 维度一：通道 -->
      <div class="card">
        <h3>按通道 <span class="hint">文字 / 图片（视觉 token 计入入参）</span></h3>
        <DonutChart :segments="summary.byChannel" />
      </div>

      <!-- 维度二三四：agent / 会话 / 模型 -->
      <div class="card">
        <h3>
          维度对比
          <span class="controls">
            <span class="seg">
              <button
                v-for="d in dims" :key="d.key"
                :class="{ active: dim === d.key }" @click="dim = d.key"
              >{{ d.label }}</button>
            </span>
            <span class="seg">
              <button
                v-for="m in metrics" :key="m.key"
                :class="{ active: metric === m.key }" @click="metric = m.key"
              >{{ m.label }}</button>
            </span>
          </span>
        </h3>
        <BarList :rows="rows" :metric="metric" />
      </div>
    </div>

    <!-- 时间轴 -->
    <div class="card">
      <h3>
        用量趋势
        <span class="controls">
          <span class="seg">
            <button :class="{ active: bucket === 'day' }" @click="bucket = 'day'">按天</button>
            <button :class="{ active: bucket === 'hour' }" @click="bucket = 'hour'">按小时</button>
          </span>
          <span class="seg" title="分线：按维度把趋势拆成多条线（画合计前 6 类）">
            <button
              v-for="g in groups" :key="g.key"
              :class="{ active: group === g.key }" @click="group = g.key"
            >{{ g.label }}</button>
          </span>
          <span class="seg">
            <button
              v-for="m in metrics" :key="m.key"
              :class="{ active: metric === m.key }" @click="metric = m.key"
            >{{ m.label }}</button>
          </span>
        </span>
      </h3>
      <TrendChart :points="series" :series="trendSeries" :extra-keys="extraKeys" :metric="metric" />
    </div>

    <!-- 明细 -->
    <div class="card">
      <h3>
        调用明细
        <span class="controls">
          <span class="hint">最近 {{ calls.length }} 条</span>
          <span class="seg">
            <button
              v-for="n in [50, 100, 200]" :key="n"
              :class="{ active: limit === n }" @click="limit = n"
            >{{ n }}</button>
          </span>
        </span>
      </h3>
      <div class="scroll">
        <table>
          <thead>
            <tr>
              <th>时间</th><th>agent</th><th>会话</th><th>动作</th><th>通道</th><th>模型</th>
              <th class="num">入参</th><th class="num">出参</th><th class="num">合计</th><th class="num">消耗</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in calls" :key="i">
              <td>{{ timeText(c.epoch) }}</td>
              <td>{{ c.agent }}</td>
              <td>{{ c.conversation }}</td>
              <td>
                <span v-if="c.firstRound" class="pill ok">首轮</span>{{ actionLabel(c.action) }}
              </td>
              <td>
                <span class="pill" :class="c.channel === '图片' ? 'warn' : 'ok'">{{ c.channel }}</span>
              </td>
              <td class="muted">{{ c.model }}</td>
              <td class="num">{{ c.reported ? fmt(c.input) : '—' }}</td>
              <td class="num">{{ c.reported ? fmt(c.output) : '—' }}</td>
              <td class="num"><b>{{ c.reported ? fmt(c.total) : '—' }}</b></td>
              <td class="num">{{ c.cost != null ? Number(c.cost).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : '—' }}</td>
            </tr>
            <tr v-if="!calls.length">
              <td colspan="10" class="muted">暂无明细</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

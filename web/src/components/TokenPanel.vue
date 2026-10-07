<script setup>
import { ref, watch, onMounted, computed } from 'vue'
import { fetchSummary, fetchTimeseries, fetchCalls, fmt, compact, timeText } from '../api.js'
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

const dims = [
  { key: 'byAgent', label: '按 agent' },
  { key: 'byConversation', label: '按会话' },
  { key: 'byModel', label: '按模型' }
]
const metrics = [
  { key: 'total', label: '合计' },
  { key: 'input', label: '入参' },
  { key: 'output', label: '出参' }
]

const rows = computed(() => (summary.value ? summary.value[dim.value] : []))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [s, t, c] = await Promise.all([
      fetchSummary(),
      fetchTimeseries(bucket.value, 30),
      fetchCalls(limit.value)
    ])
    summary.value = s
    series.value = t
    calls.value = c
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

watch(() => props.tick, load)
watch(bucket, load)
watch(limit, load)
onMounted(load)
</script>

<template>
  <div class="grid" style="gap:16px">
    <div v-if="error" class="card error">加载失败：{{ error }}（机器人是否在 8080 运行？）</div>
    <div v-if="loading && !summary" class="card muted">加载中…</div>

    <!-- 总账 -->
    <div class="grid cards" v-if="summary">
      <StatCard label="调用次数" :value="fmt(summary.total.calls)" :sub="`端点未返回用量 ${summary.noUsageCalls} 次`" />
      <StatCard label="入参 token（含图片）" :value="compact(summary.total.input)" :sub="fmt(summary.total.input)" />
      <StatCard label="出参 token" :value="compact(summary.total.output)" :sub="fmt(summary.total.output)" />
      <StatCard label="合计 token" :value="compact(summary.total.total)" sub="跨重启累计（data/token-usage.jsonl）" />
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
          <span class="seg">
            <button
              v-for="m in metrics" :key="m.key"
              :class="{ active: metric === m.key }" @click="metric = m.key"
            >{{ m.label }}</button>
          </span>
        </span>
      </h3>
      <TrendChart :points="series" :metric="metric" />
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
              <th>时间</th><th>agent</th><th>会话</th><th>通道</th><th>模型</th>
              <th class="num">入参</th><th class="num">出参</th><th class="num">合计</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in calls" :key="i">
              <td>{{ timeText(c.epoch) }}</td>
              <td>{{ c.agent }}</td>
              <td>{{ c.conversation }}</td>
              <td>
                <span class="pill" :class="c.channel === '图片' ? 'warn' : 'ok'">{{ c.channel }}</span>
              </td>
              <td class="muted">{{ c.model }}</td>
              <td class="num">{{ c.reported ? fmt(c.input) : '—' }}</td>
              <td class="num">{{ c.reported ? fmt(c.output) : '—' }}</td>
              <td class="num"><b>{{ c.reported ? fmt(c.total) : '—' }}</b></td>
            </tr>
            <tr v-if="!calls.length">
              <td colspan="8" class="muted">暂无明细</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

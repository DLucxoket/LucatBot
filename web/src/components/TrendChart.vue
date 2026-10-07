<script setup>
import { computed } from 'vue'
import { compact } from '../api.js'

const props = defineProps({
  /** [{ bucket, total, input, output, calls }]（bucket 升序） */
  points: { type: Array, default: () => [] },
  metric: { type: String, default: 'total' }
})

const W = 720
const H = 220
const PAD = { l: 52, r: 14, t: 16, b: 30 }

const values = computed(() => props.points.map((p) => Number(p[props.metric]) || 0))
const max = computed(() => Math.max(1, ...values.value))
const xOf = (i) =>
  PAD.l + (props.points.length <= 1 ? 0 : (i * (W - PAD.l - PAD.r)) / (props.points.length - 1))
const yOf = (v) => H - PAD.b - (v / max.value) * (H - PAD.t - PAD.b)

const linePath = computed(() =>
  values.value.map((v, i) => `${i ? 'L' : 'M'}${xOf(i).toFixed(1)},${yOf(v).toFixed(1)}`).join(' ')
)
const areaPath = computed(() => {
  if (!values.value.length) return ''
  const last = values.value.length - 1
  return `${linePath.value} L${xOf(last).toFixed(1)},${H - PAD.b} L${xOf(0).toFixed(1)},${H - PAD.b} Z`
})
const yTicks = computed(() =>
  [0, 0.5, 1].map((f) => ({ v: max.value * f, y: yOf(max.value * f) }))
)
const labelStep = computed(() => Math.max(1, Math.ceil(props.points.length / 9)))
</script>

<template>
  <div v-if="!points.length" class="muted">暂无数据——跑几轮决策后这里会出现趋势</div>
  <svg v-else :viewBox="`0 0 ${W} ${H}`" style="width:100%; height:auto; display:block">
    <!-- 横向网格 + y 轴刻度 -->
    <g>
      <line
        v-for="t in yTicks" :key="t.y"
        :x1="PAD.l" :x2="W - PAD.r" :y1="t.y" :y2="t.y"
        stroke="#2a3245" stroke-dasharray="3 4"
      />
      <text
        v-for="t in yTicks" :key="'l' + t.y"
        :x="PAD.l - 8" :y="t.y + 4" text-anchor="end" fill="#8d97ad" font-size="11"
      >{{ compact(t.v) }}</text>
    </g>

    <!-- 面积 + 折线 -->
    <path :d="areaPath" fill="rgba(91,140,255,.18)" />
    <path :d="linePath" fill="none" stroke="#5b8cff" stroke-width="2.4" stroke-linejoin="round" />

    <!-- 数据点（原生 tooltip） -->
    <circle
      v-for="(p, i) in points" :key="i"
      :cx="xOf(i)" :cy="yOf(values[i])" r="3.4"
      fill="#0f1420" stroke="#5b8cff" stroke-width="2"
    >
      <title>{{ p.bucket }}：{{ p[metric] }}（{{ p.calls }} 次）</title>
    </circle>

    <!-- x 轴标签（隔几个取一个，防重叠） -->
    <text
      v-for="(p, i) in points" :key="'x' + i"
      v-show="i % labelStep === 0 || i === points.length - 1"
      :x="xOf(i)" :y="H - 8" text-anchor="middle" fill="#8d97ad" font-size="11"
    >{{ p.bucket.slice(5) }}</text>
  </svg>
</template>

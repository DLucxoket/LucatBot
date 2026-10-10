<script setup>
import { computed } from 'vue'
import { compact } from '../api.js'

const props = defineProps({
  /** 单线模式：[{ bucket, total, input, output, calls }]（bucket 升序） */
  points: { type: Array, default: () => [] },
  /**
   * 分线模式（分类趋势）：[{ name, total, points: [{bucket,calls,input,output,total}] }]
   * ——各线的 points 已按同一 bucket 轴对齐；**非空时优先于 points**
   */
  series: { type: Array, default: () => [] },
  /** 参与画线但被截断（只画前 N）的剩余分类数——图例尾部提示用 */
  extraKeys: { type: Number, default: 0 },
  metric: { type: String, default: 'total' }
})

const COLORS = ['#5b8cff', '#42d392', '#ffb454', '#ff6b6b', '#b48cff', '#4fc3f7', '#f06292', '#90a4ae']

const W = 720
const H = 220
const PAD = { l: 52, r: 14, t: 16, b: 30 }

/** 统一成「线」列表：单线模式 = 一条合计线 */
const multi = computed(() => props.series.length > 0)
const lines = computed(() =>
  multi.value ? props.series : [{ name: '合计', points: props.points }]
)
/** x 轴时间桶（分线模式取第一条线的点轴——调用方已对齐） */
const axis = computed(() => lines.value[0].points.map((p) => p.bucket))

const valOf = (line, i) => Number(line.points[i]?.[props.metric]) || 0
const max = computed(() =>
  Math.max(1, ...lines.value.flatMap((l) => l.points.map((p) => Number(p[props.metric]) || 0)))
)
const xOf = (i) =>
  PAD.l + (axis.value.length <= 1 ? 0 : (i * (W - PAD.l - PAD.r)) / (axis.value.length - 1))
const yOf = (v) => H - PAD.b - (v / max.value) * (H - PAD.t - PAD.b)
const colorOf = (i) => COLORS[i % COLORS.length]

const pathOf = (line) =>
  line.points.map((p, i) => `${i ? 'L' : 'M'}${xOf(i).toFixed(1)},${yOf(valOf(line, i)).toFixed(1)}`).join(' ')

/** 面积填充只给单线模式（多线叠面积会糊成一团） */
const areaPath = computed(() => {
  if (multi.value || !axis.value.length) return ''
  const line = lines.value[0]
  const last = line.points.length - 1
  return `${pathOf(line)} L${xOf(last).toFixed(1)},${H - PAD.b} L${xOf(0).toFixed(1)},${H - PAD.b} Z`
})
const yTicks = computed(() =>
  [0, 0.5, 1].map((f) => ({ v: max.value * f, y: yOf(max.value * f) }))
)
const labelStep = computed(() => Math.max(1, Math.ceil(axis.value.length / 9)))

/** 图例：每条线的名字 + 区间合计（随 入参/出参/合计 切换） */
const legend = computed(() =>
  lines.value.map((l, i) => ({
    name: l.name,
    color: colorOf(i),
    sum: l.points.reduce((a, p) => a + (Number(p[props.metric]) || 0), 0)
  }))
)
</script>

<template>
  <div v-if="!points.length && !series.length" class="muted">暂无数据——跑几轮决策后这里会出现趋势</div>

  <template v-else>
    <svg :viewBox="`0 0 ${W} ${H}`" style="width:100%; height:auto; display:block">
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

      <!-- 面积（单线）+ 折线（每条线一色） -->
      <path v-if="!multi" :d="areaPath" fill="rgba(91,140,255,.18)" />
      <path
        v-for="(l, li) in lines" :key="'p' + li"
        :d="pathOf(l)" fill="none" :stroke="colorOf(li)"
        stroke-width="2.2" stroke-linejoin="round"
      />

      <!-- 数据点（原生 tooltip；分线时带分类名） -->
      <g v-for="(l, li) in lines" :key="'g' + li">
        <circle
          v-for="(p, i) in l.points" :key="i"
          :cx="xOf(i)" :cy="yOf(valOf(l, i))" r="3.2"
          fill="#0f1420" :stroke="colorOf(li)" stroke-width="2"
        >
          <title v-if="multi">{{ p.bucket }} · {{ l.name }}：{{ p[metric] }}（{{ p.calls }} 次）</title>
          <title v-else>{{ p.bucket }}：{{ p[metric] }}（{{ p.calls }} 次）</title>
        </circle>
      </g>

      <!-- x 轴标签（隔几个取一个，防重叠） -->
      <text
        v-for="(b, i) in axis" :key="'x' + i"
        v-show="i % labelStep === 0 || i === axis.length - 1"
        :x="xOf(i)" :y="H - 8" text-anchor="middle" fill="#8d97ad" font-size="11"
      >{{ b.slice(5) }}</text>
    </svg>

    <!-- 图例（分线模式才显示；随 入参/出参/合计 变化） -->
    <div v-if="multi" class="legend">
      <span v-for="(it, i) in legend" :key="i" class="legend-item">
        <i :style="{ background: it.color }"></i>{{ it.name }}
        <b>{{ compact(it.sum) }}</b>
      </span>
      <span v-if="extraKeys > 0" class="muted">…等 {{ extraKeys }} 类未画（只画合计前几）</span>
    </div>
  </template>
</template>

<script setup>
import { computed } from 'vue'
import { compact } from '../api.js'

const props = defineProps({
  /** [{ name, total }] */
  segments: { type: Array, default: () => [] },
  colors: { type: Array, default: () => ['#5b8cff', '#42d392', '#ffb454', '#ff6b6b'] }
})

const R = 60
const C = 2 * Math.PI * R
const total = computed(() => Math.max(1, props.segments.reduce((s, x) => s + (Number(x.total) || 0), 0)))

/** 每段的 dasharray / 偏移（按顺序排布） */
const arcs = computed(() => {
  let offset = 0
  return props.segments.map((seg, i) => {
    const frac = (Number(seg.total) || 0) / total.value
    const len = frac * C
    const arc = { name: seg.name, value: seg.total, color: props.colors[i % props.colors.length], len, offset }
    offset += len
    return arc
  })
})

/** SVG stroke-dasharray（模板里不能用反引号字面量，放这里算） */
const dashOf = (a) => `${a.len} ${C - a.len}`
</script>

<template>
  <div style="display:flex; gap:18px; align-items:center; flex-wrap:wrap">
    <svg :width="150" :height="150" viewBox="0 0 150 150">
      <circle cx="75" cy="75" :r="R" fill="none" stroke="#1d2435" stroke-width="22" />
      <circle
        v-for="a in arcs"
        :key="a.name"
        cx="75" cy="75" :r="R" fill="none"
        :stroke="a.color" stroke-width="22"
        :stroke-dasharray="dashOf(a)"
        :stroke-dashoffset="-a.offset"
        transform="rotate(-90 75 75)"
      >
        <title>{{ a.name }}：{{ a.value }}</title>
      </circle>
      <text x="75" y="70" text-anchor="middle" fill="#e6ebf5" font-size="20" font-weight="700">
        {{ compact(total) }}
      </text>
      <text x="75" y="90" text-anchor="middle" fill="#8d97ad" font-size="11">总 token</text>
    </svg>

    <div class="list" style="flex:1; min-width:160px">
      <div v-for="a in arcs" :key="a.name" class="item" style="display:flex; align-items:center; gap:8px">
        <i :style="{ width: '10px', height: '10px', borderRadius: '3px', background: a.color, display: 'inline-block' }"></i>
        <span style="flex:1">{{ a.name }}</span>
        <b>{{ compact(a.value) }}</b>
        <span class="muted">{{ ((Number(a.value) || 0) / total * 100).toFixed(1) }}%</span>
      </div>
    </div>
  </div>
</template>

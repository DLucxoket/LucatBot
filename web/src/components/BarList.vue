<script setup>
import { computed } from 'vue'
import { compact, fmt } from '../api.js'

const props = defineProps({
  /** [{ name, calls, input, output, total }] */
  rows: { type: Array, default: () => [] },
  metric: { type: String, default: 'total' }
})

const max = computed(() => Math.max(1, ...props.rows.map((r) => Number(r[props.metric]) || 0)))
const valueOf = (r) => Number(r[props.metric]) || 0
</script>

<template>
  <div v-if="!rows.length" class="muted">暂无数据</div>
  <div v-else>
    <div v-for="row in rows" :key="row.name" class="bar-row">
      <div class="name" :title="row.name">{{ row.name }}</div>
      <div class="track">
        <div class="fill" :style="{ width: (valueOf(row) / max) * 100 + '%' }"></div>
      </div>
      <div class="val">
        <b>{{ compact(valueOf(row)) }}</b>
        <span class="muted"> · {{ fmt(row.calls) }}次</span>
      </div>
    </div>
  </div>
</template>

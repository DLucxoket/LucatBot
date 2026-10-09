<script setup>
import { ref } from 'vue'
import TokenPanel from './components/TokenPanel.vue'
import TracePanel from './components/TracePanel.vue'
import StatePanel from './components/StatePanel.vue'

const tab = ref('token')
const auto = ref(false)
const tick = ref(0)
let timer = null

function toggleAuto() {
  auto.value = !auto.value
  if (timer) { clearInterval(timer); timer = null }
  if (auto.value) timer = setInterval(() => { tick.value++ }, 10_000)
}
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        LucatBot 控制台
        <small>token 用量 · 调用轨迹 · 机器人状态（只读 API，不触发任何 LLM 调用）</small>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: tab === 'token' }" @click="tab = 'token'">Token 用量</button>
        <button class="tab" :class="{ active: tab === 'trace' }" @click="tab = 'trace'">调用轨迹</button>
        <button class="tab" :class="{ active: tab === 'state' }" @click="tab = 'state'">机器人状态</button>
      </div>

      <button class="btn" :class="{ on: auto }" @click="toggleAuto">
        {{ auto ? '自动刷新中' : '自动刷新' }}
      </button>
    </header>

    <TokenPanel v-if="tab === 'token'" :tick="tick" />
    <TracePanel v-else-if="tab === 'trace'" :tick="tick" />
    <StatePanel v-else :tick="tick" />
  </div>
</template>

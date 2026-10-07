<script setup>
import { ref, watch, onMounted, computed } from 'vue'
import { fetchState, timeText, agoText, fmt } from '../api.js'
import StatCard from './StatCard.vue'

const props = defineProps({ tick: { type: Number, default: 0 } })

const state = ref(null)
const error = ref('')

const stateText = (s) => {
  if (!s) return '-'
  if (s.startsWith('ViewingConversations')) return '消息列表'
  if (s.startsWith('InConversation')) return '会话中'
  if (s.startsWith('Idle')) return '空闲'
  return s
}
const typeText = (t) =>
  ({ FACT: '事实', OPINION: '看法', EVENT: '事件', RELATION: '关系' })[t] || t

const pending = computed(() => state.value?.pendingTodos || [])
const done = computed(() => state.value?.doneTodos || [])
const memories = computed(() => state.value?.recentMemories || [])
const actionLog = computed(() => [...(state.value?.actionLog || [])].reverse())
const unreadEntries = computed(() => Object.entries(state.value?.unread || {}))

async function load() {
  error.value = ''
  try {
    state.value = await fetchState()
  } catch (e) {
    error.value = e.message || String(e)
  }
}

watch(() => props.tick, load)
onMounted(load)
</script>

<template>
  <div class="grid" style="gap:16px">
    <div v-if="error" class="card error">加载失败：{{ error }}（机器人是否在 8080 运行？）</div>
    <div v-if="!state && !error" class="card muted">加载中…</div>

    <template v-if="state">
      <!-- 心情 + 关键指标 -->
      <div class="grid cards">
        <div class="card stat">
          <div class="label">当前状态</div>
          <div class="value">{{ stateText(state.state) }}</div>
          <div class="sub">{{ timeText(state.serverTime) }}</div>
        </div>

        <div class="card stat">
          <div class="label">心情</div>
          <div class="value" style="font-size:20px">{{ state.mood.text }}</div>
          <div class="mood-bar"><i :style="{ width: (state.mood.intensity * 100).toFixed(0) + '%' }"></i></div>
          <div class="sub">{{ state.mood.type }} · 强度 {{ state.mood.intensity.toFixed(2) }}</div>
        </div>

        <StatCard label="待办" :value="pending.length" :sub="`最近完成 ${done.length} 条`" />
        <StatCard
          label="记忆总数"
          :value="fmt(state.memoryTotal)"
          :sub="Object.entries(state.memoryByType).map(([k, v]) => `${typeText(k)} ${v}`).join(' · ') || '暂无'"
        />
      </div>

      <div class="grid two">
        <!-- 待办 -->
        <div class="card">
          <h3>待办清单 <span class="hint">未完成 {{ pending.length }}</span></h3>
          <div class="list scroll">
            <div v-for="t in pending" :key="t.id" class="item">
              <div>{{ t.content }}</div>
              <div class="meta">
                {{ t.conversationId || '未关联会话' }} · 记于 {{ timeText(t.createdAt) }}
              </div>
            </div>
            <div v-if="!pending.length" class="muted">没有未完成的待办</div>
          </div>
          <h3 style="margin-top:16px">最近完成</h3>
          <div class="list">
            <div v-for="t in done" :key="t.id" class="item done">
              <div>{{ t.content }}</div>
              <div class="meta">完成于 {{ timeText(t.completedAt) }}</div>
            </div>
            <div v-if="!done.length" class="muted">还没有完成记录</div>
          </div>
        </div>

        <!-- 记忆 -->
        <div class="card">
          <h3>记忆 <span class="hint">最近 {{ memories.length }} 条 / 共 {{ state.memoryTotal }}</span></h3>
          <div style="margin-bottom:10px">
            <span
              v-for="(v, k) in state.memoryByType" :key="k"
              class="pill"
            >{{ typeText(k) }} {{ v }}</span>
          </div>
          <div class="list scroll">
            <div v-for="(m, i) in memories" :key="i" class="item">
              <div>{{ m.content }}</div>
              <div class="meta">{{ typeText(m.type) }} · {{ agoText(m.createdAt) }}</div>
            </div>
            <div v-if="!memories.length" class="muted">还没有记忆</div>
          </div>
        </div>
      </div>

      <div class="grid two">
        <!-- 操作日志 -->
        <div class="card">
          <h3>最近操作 <span class="hint">共 {{ state.actionLog.length }} 条</span></h3>
          <div class="list scroll">
            <div v-for="(a, i) in actionLog" :key="i" class="item">
              <div>{{ a.action }} <span v-if="a.reason" class="muted">（{{ a.reason }}）</span></div>
              <div class="meta">{{ timeText(a.timestamp, false) }} · {{ a.conversationId || '全局' }}</div>
            </div>
            <div v-if="!actionLog.length" class="muted">暂无操作</div>
          </div>
        </div>

        <!-- 会话 -->
        <div class="card">
          <h3>会话 <span class="hint">未读 {{ unreadEntries.length }} 个会话</span></h3>
          <div class="list" style="margin-bottom:12px">
            <div v-for="[conv, n] in unreadEntries" :key="conv" class="item" style="display:flex; justify-content:space-between">
              <span>{{ conv }}</span>
              <span class="pill warn">{{ n }} 条未读</span>
            </div>
            <div v-if="!unreadEntries.length" class="muted">没有未读</div>
          </div>
          <h3>最近活跃（24h 内）</h3>
          <div class="list">
            <div v-for="c in state.recentConversations" :key="c" class="item">{{ c }}</div>
            <div v-if="!state.recentConversations.length" class="muted">最近没有会话</div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

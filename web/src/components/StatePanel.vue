<script setup>
import { ref, reactive, watch, onMounted, computed } from 'vue'
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

/** 预计执行时间的编辑草稿（id → datetime-local 值，本机时区） */
const planDraft = reactive({})

/** epoch → `datetime-local` 需要的 `yyyy-MM-ddTHH:mm` */
function toLocalInput(epoch) {
  if (!epoch) return ''
  const d = new Date(Number(epoch))
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 设置预计执行时间（PUT …/planned-at?epoch=…） */
async function setPlanned(t) {
  const raw = planDraft[t.id]
  if (!raw) {
    error.value = '先在输入框选时间，再点「设预计」'
    return
  }
  const epoch = new Date(raw).getTime()
  if (Number.isNaN(epoch)) {
    error.value = '时间格式不对，换个写法试试'
    return
  }
  error.value = ''
  try {
    const res = await fetch(`/api/todos/${encodeURIComponent(t.id)}/planned-at?epoch=${epoch}`, { method: 'PUT' })
    if (res.status === 404) throw new Error('这条待办已经不在了')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data = await res.json()
    t.plannedAt = data.plannedAt // 就地更新（ref 深层响应式）
  } catch (e) {
    error.value = `设置失败：${e.message || e}`
  }
}

/** 清除预计执行时间（PUT 不带 epoch） */
async function clearPlanned(t) {
  error.value = ''
  try {
    const res = await fetch(`/api/todos/${encodeURIComponent(t.id)}/planned-at`, { method: 'PUT' })
    if (res.status === 404) throw new Error('这条待办已经不在了')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    t.plannedAt = null
    planDraft[t.id] = ''
  } catch (e) {
    error.value = `清除失败：${e.message || e}`
  }
}

async function load() {
  error.value = ''
  try {
    state.value = await fetchState()
    // 已设的预计回填进输入框，方便微调
    for (const t of state.value.pendingTodos || []) {
      if (t.plannedAt) planDraft[t.id] = toLocalInput(t.plannedAt)
    }
  } catch (e) {
    error.value = e.message || String(e)
  }
}

/** 取消一条待办（DELETE /api/todos/{id}）——就地移除，不必整页重取 */
async function cancelTodo(t) {
  error.value = ''
  try {
    const res = await fetch(`/api/todos/${encodeURIComponent(t.id)}`, { method: 'DELETE' })
    if (res.status === 404) throw new Error('这条待办已经不在了（可能刚被取消）')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data = await res.json()
    state.value.pendingTodos = state.value.pendingTodos.filter((x) => x.id !== data.id)
  } catch (e) {
    error.value = `取消失败：${e.message || e}`
  }
}

/** 清空全部未完成（已完成是历史痕迹，不动）——治「后台一直挂着 N 条陈年待办」 */
async function clearPending() {
  const n = state.value?.pendingTodos?.length || 0
  if (!n) return
  if (!window.confirm(`确定清空全部 ${n} 条未完成待办？（已完成的不动，不可撤销）`)) return
  error.value = ''
  try {
    const res = await fetch('/api/todos', { method: 'DELETE' })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    await load() // 重新拉 overview：待办与操作日志一起刷新
  } catch (e) {
    error.value = `清空失败：${e.message || e}`
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
          <h3>
            待办清单
            <span class="controls">
              <span class="hint">未完成 {{ pending.length }}</span>
              <button v-if="pending.length" class="btn small" @click="clearPending">清空未完成</button>
            </span>
          </h3>
          <div class="list scroll">
            <div v-for="t in pending" :key="t.id" class="item">
              <div style="display:flex; justify-content:space-between; gap:10px; align-items:flex-start">
                <div>
                  <div>{{ t.content }}</div>
                  <div class="meta">
                    建于 {{ timeText(t.createdAt) }}<template v-if="t.plannedAt"> · 预计 {{ timeText(t.plannedAt) }}</template>
                    <span v-if="t.conversationId"> · {{ t.conversationId }}</span>
                  </div>
                </div>
                <button class="btn small" @click="cancelTodo(t)">取消</button>
              </div>
              <div class="controls" style="margin-top:8px">
                <input v-model="planDraft[t.id]" type="datetime-local" class="time-input" />
                <button class="btn small" @click="setPlanned(t)">设预计</button>
                <button v-if="t.plannedAt" class="btn small" @click="clearPlanned(t)">清预计</button>
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

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { fetchCalls, fetchCallDetail, fmt, timeText, actionLabel } from '../api.js'

const props = defineProps({ tick: { type: Number, default: 0 } })

const rows = ref([])
const loading = ref(false)
const error = ref('')
const limit = ref(100)
const query = ref('')

const selectedId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const tab = ref('overview') // overview | request | response
const copied = ref('')

const TABS = [
  { key: 'overview', label: '概述' },
  { key: 'request', label: '请求' },
  { key: 'response', label: '响应' }
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    const list = await fetchCalls(limit.value)
    rows.value = list
    // 选中项还在就保留（刷新不跳）；否则选第一条
    if (!list.some((r) => r.id === selectedId.value)) {
      if (list.length) await open(list[0].id)
      else {
        selectedId.value = null
        detail.value = null
      }
    }
  } catch (e) {
    error.value = e.message || String(e)
  } finally {
    loading.value = false
  }
}

async function open(id) {
  if (id == null) return
  selectedId.value = id
  detailLoading.value = true
  detailError.value = ''
  tab.value = 'overview'
  try {
    detail.value = await fetchCallDetail(id)
  } catch (e) {
    detail.value = null
    detailError.value = e.message || String(e)
  } finally {
    detailLoading.value = false
  }
}

/** 左栏过滤：agent / 会话 / 动作 / 模型 全文匹配（列表本身 ≤200 条，纯前端够用） */
const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter((r) =>
    [r.agent, r.conversation, r.action, r.model].some((v) =>
      String(v ?? '').toLowerCase().includes(q)
    )
  )
})

const bodyLen = (s) => (s ? s.length : 0)

async function copy(text, which) {
  try {
    await navigator.clipboard.writeText(text || '')
    copied.value = which
    setTimeout(() => { if (copied.value === which) copied.value = '' }, 1200)
  } catch (_) {
    /* 剪贴板不可用（非 https/无权限）就静默 */
  }
}

watch(() => props.tick, load)
watch(limit, load)
onMounted(load)
</script>

<template>
  <div class="grid" style="gap:16px">
    <div v-if="error" class="card error">加载失败：{{ error }}（机器人是否在 8080 运行？）</div>

    <div class="card">
      <h3>
        调用轨迹
        <span class="hint">每一次 LLM 调用的真实请求 / 回复 / 耗时 / 消耗——左栏选一次调用，右栏看详情</span>
        <span class="controls">
          <input class="search-input" v-model="query" placeholder="搜 agent / 会话 / 动作" />
          <span class="seg">
            <button
              v-for="n in [50, 100, 200]" :key="n"
              :class="{ active: limit === n }" @click="limit = n"
            >{{ n }}</button>
          </span>
          <button class="btn" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
        </span>
      </h3>

      <div class="trace-split">
        <!-- 左：调用列表（时间倒序） -->
        <div class="trace-list scroll">
          <div v-if="loading && !rows.length" class="muted">加载中…</div>
          <div
            v-for="c in filtered" :key="c.id ?? c.epoch"
            class="trace-item" :class="{ active: c.id === selectedId }"
            @click="open(c.id)"
          >
            <div class="trace-item-head">
              <span class="pill">{{ c.agent }}</span>
              <span class="pill ok">{{ actionLabel(c.action) }}</span>
              <span v-if="c.error" class="pill warn">失败</span>
              <span v-if="c.firstRound" class="pill">首轮</span>
              <span class="muted time">{{ timeText(c.epoch, false) }}</span>
            </div>
            <div class="trace-item-sub">
              {{ c.conversation }} · 入 {{ fmt(c.input) }} / 出 {{ fmt(c.output) }} ·
              {{ c.durationMs != null ? c.durationMs + ' ms' : '—' }}
              <span v-if="!c.reported" class="pill warn">未返回用量</span>
            </div>
          </div>
          <div v-if="!loading && !filtered.length" class="muted">没有匹配的调用</div>
        </div>

        <!-- 右：详情（概述 / 请求 / 响应） -->
        <div class="card trace-detail">
          <h3>
            详情
            <span class="hint" v-if="detail">#{{ detail.id }} · {{ timeText(detail.epoch) }}</span>
            <span class="controls">
              <span class="seg">
                <button
                  v-for="t in TABS" :key="t.key"
                  :class="{ active: tab === t.key }" @click="tab = t.key"
                >{{ t.label }}</button>
              </span>
            </span>
          </h3>

          <div v-if="detailLoading" class="muted">加载中…</div>
          <div v-else-if="detailError" class="error">{{ detailError }}</div>

          <template v-else-if="detail">
            <!-- 概述 -->
            <div v-if="tab === 'overview'">
              <table class="kv">
                <tbody>
                  <tr><td>时间</td><td>{{ timeText(detail.epoch) }}</td></tr>
                  <tr><td>agent</td><td>{{ detail.agent }}</td></tr>
                  <tr><td>会话</td><td>{{ detail.conversation }}</td></tr>
                  <tr><td>通道 / 模型</td><td>{{ detail.channel }} · {{ detail.model }}</td></tr>
                  <tr><td>动作</td><td>{{ actionLabel(detail.action) }} <span v-if="detail.firstRound" class="pill">进场首轮</span></td></tr>
                  <tr><td>token</td>
                    <td>入 {{ fmt(detail.input) }} · 出 {{ fmt(detail.output) }} · 合计 <b>{{ fmt(detail.total) }}</b>
                      <span v-if="!detail.reported" class="pill warn">端点未返回用量（按 0 计）</span>
                    </td>
                  </tr>
                  <tr><td>耗时</td><td>{{ detail.durationMs != null ? detail.durationMs + ' ms' : '（旧数据未记录）' }}</td></tr>
                  <tr><td>请求 / 回复</td><td>{{ bodyLen(detail.request) }} / {{ bodyLen(detail.response) }} 字符</td></tr>
                </tbody>
              </table>
              <div v-if="detail.error" class="trace-error">端点异常：{{ detail.error }}</div>
            </div>

            <!-- 请求：发给 LLM 的真实内容 -->
            <div v-else-if="tab === 'request'">
              <div class="controls" style="margin-bottom:8px">
                <span class="hint">{{ bodyLen(detail.request) }} 字符（图片以 [图片 ref] 标签呈现，data URI 略）</span>
                <button class="btn small" @click="copy(detail.request, 'req')">{{ copied === 'req' ? '已复制' : '复制' }}</button>
              </div>
              <pre class="code-block">{{ detail.request || '（无——这条是旧数据，没记请求正文）' }}</pre>
            </div>

            <!-- 响应：模型回复 -->
            <div v-else>
              <div class="controls" style="margin-bottom:8px">
                <span class="hint">{{ bodyLen(detail.response) }} 字符</span>
                <button class="btn small" @click="copy(detail.response, 'resp')">{{ copied === 'resp' ? '已复制' : '复制' }}</button>
              </div>
              <pre class="code-block">{{ detail.response || '（空回复——端点异常/被截断，见下方异常信息）' }}</pre>
              <div v-if="detail.error" class="trace-error">端点异常：{{ detail.error }}</div>
            </div>
          </template>

          <div v-else class="muted">左侧选一次调用查看真实请求与回复</div>
        </div>
      </div>
    </div>
  </div>
</template>

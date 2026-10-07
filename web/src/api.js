/** 面板数据源——全部只读 GET；dev 起 vite 时 /api 代理到 8080（见 vite.config.js） */
async function getJson(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!res.ok) throw new Error(`${url} → HTTP ${res.status}`)
  return res.json()
}

/** token 汇总：total + byChannel/byAgent/byConversation/byModel + noUsageCalls */
export const fetchSummary = () => getJson('/api/token/summary')

/** token 时间轴：bucket=day|hour，limit=返回最近几个桶 */
export const fetchTimeseries = (bucket = 'day', limit = 30) =>
  getJson(`/api/token/timeseries?bucket=${encodeURIComponent(bucket)}&limit=${limit}`)

/** token 明细：最近 limit 次调用（时间倒序） */
export const fetchCalls = (limit = 200) => getJson(`/api/token/calls?limit=${limit}`)

/** 机器人状态总览：心情/待办/记忆/操作日志/未读 */
export const fetchState = () => getJson('/api/state/overview')

// ------------------------------------------------------------------
// 展示用格式化
// ------------------------------------------------------------------

/** 千分位 */
export const fmt = (n) => (Number.isFinite(Number(n)) ? Number(n).toLocaleString('zh-CN') : '-')

/** 压缩显示：12345 → 12.3k */
export function compact(n) {
  const v = Number(n) || 0
  if (v >= 1e8) return `${(v / 1e8).toFixed(2)}亿`
  if (v >= 1e4) return `${(v / 1e4).toFixed(1)}万`
  if (v >= 1e3) return `${(v / 1e3).toFixed(1)}k`
  return String(v)
}

/** epoch 毫秒 → 本地时间串 */
export const timeText = (epoch, withDate = true) => {
  if (!epoch) return '-'
  const d = new Date(Number(epoch))
  const p = (x) => String(x).padStart(2, '0')
  const date = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
  const time = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  return withDate ? `${date} ${time}` : time
}

/** 「多久之前」（前端本地时区） */
export function agoText(epoch) {
  if (!epoch) return '-'
  const diff = Date.now() - Number(epoch)
  if (diff < 0) return '刚刚'
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  return `${Math.floor(h / 24)} 天前`
}

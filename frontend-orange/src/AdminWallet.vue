<script setup>
import { inject, onMounted, ref } from 'vue'
const props = defineProps({ errorText: Function })
const emit = defineEmits(['updated'])
const api = inject('api'), tab = ref('adjust'), users = ref([]), codes = ref([]), ledger = ref([]), query = ref(''), filter = ref(''), busy = ref(false), error = ref(''), success = ref(''), moreCodes = ref(false), moreLedger = ref(false)
const adjustment = ref({ username: '', direction: 'add', amount: '', remark: '' }), batch = ref({ amount: '50.00', quantity: 10 })
const latestBatch = ref([])
let adjustRequest = null, batchRequest = null
const money = value => `¥${Number(value || 0).toFixed(2)}`
const time = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
const labels = { RECHARGE: '充值', CONSUME: '消费', REFUND: '退款', ADMIN_ADJUST: '调账' }
const newId = () => crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => { const a = crypto.getRandomValues(new Uint8Array(1))[0] & 15; return (c === 'x' ? a : (a & 3) | 8).toString(16) })
function requestFor(previous, payload) { return previous?.payload === JSON.stringify(payload) ? previous : { payload: JSON.stringify(payload), id: newId() } }
async function loadUsers() { try { users.value = (await api.get('/admin/wallet/users', { params: { q: query.value } })).data } catch (e) { error.value = props.errorText(e) } }
async function loadCodes(append = false) { const data = (await api.get('/admin/wallet/recharge-codes', { params: append ? { before: codes.value.at(-1).id } : {} })).data; codes.value = append ? [...codes.value, ...data] : data; moreCodes.value = data.length === 100 }
async function loadLedger(append = false) { const data = (await api.get('/admin/wallet/transactions', { params: { username: filter.value, ...(append ? { before: ledger.value.at(-1).id } : {}) } })).data; ledger.value = append ? [...ledger.value, ...data] : data; moreLedger.value = data.length === 30 }
async function refresh() { error.value = ''; try { await Promise.all([loadUsers(), loadCodes(), loadLedger()]); emit('updated') } catch (e) { error.value = props.errorText(e) } }
async function adjust() {
  if (busy.value) return
  if (!/^[0-9]{1,8}(\.[0-9]{1,2})?$/.test(adjustment.value.amount) || Number(adjustment.value.amount) <= 0) { error.value = '请输入大于零的金额，最多两位小数'; return }
  const data = { username: adjustment.value.username.trim(), amount: `${adjustment.value.direction === 'deduct' ? '-' : ''}${adjustment.value.amount}`, remark: adjustment.value.remark.trim() }
  if (!data.username || !data.remark) { error.value = '请填写用户名和调账原因'; return }
  if (!window.confirm(`确认${adjustment.value.direction === 'deduct' ? '扣减' : '增加'} ${data.username} 的余额 ${money(adjustment.value.amount)}？`)) return
  adjustRequest = requestFor(adjustRequest, data); busy.value = true; error.value = ''; success.value = ''
  try { const result = (await api.post('/admin/wallet/adjust', { ...data, requestId: adjustRequest.id })).data; success.value = result.message; adjustRequest = null; adjustment.value.amount = ''; adjustment.value.remark = ''; await refresh() } catch (e) { error.value = props.errorText(e) } finally { busy.value = false }
}
async function generate() {
  if (busy.value) return
  const amount = String(batch.value.amount).trim(), quantity = Number(batch.value.quantity)
  if (!/^[0-9]{1,8}(\.[0-9]{1,2})?$/.test(amount) || Number(amount) <= 0) { error.value = '请输入 0.01 至 99999999.99 的面额，最多两位小数'; return }
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 100) { error.value = '每批只能生成 1–100 张充值码'; return }
  const data = { amount, quantity }
  batchRequest = requestFor(batchRequest, data); busy.value = true; error.value = ''; success.value = ''
  try { const result = (await api.post('/admin/wallet/recharge-codes', { ...data, requestId: batchRequest.id })).data; latestBatch.value = result.codes; batchRequest = null; success.value = `${result.codes.length} 张充值码已生成`; await loadCodes() } catch (e) { error.value = props.errorText(e) } finally { busy.value = false }
}
async function copy(value) {
  error.value = ''; success.value = ''
  if (!value) return
  try {
    if (window.isSecureContext && navigator.clipboard) await navigator.clipboard.writeText(value)
    else {
      const field = document.createElement('textarea'), previous = document.activeElement
      field.value = value; field.style.position = 'fixed'; field.style.opacity = '0'; document.body.appendChild(field); field.select()
      try { if (!document.execCommand('copy')) throw new Error('Clipboard unavailable') }
      finally { field.remove(); previous?.focus() }
    }
    success.value = '已复制，请只发送给对应买家'
  } catch { error.value = '浏览器未允许自动复制，请选中充值码手动复制' }
}
async function more(kind) { try { if (kind === 'codes') await loadCodes(true); else await loadLedger(true) } catch (e) { error.value = props.errorText(e) } }
onMounted(refresh)
</script>

<template>
  <section class="content-page wallet-admin"><div class="page-heading"><div><span class="overline">WALLET & STORED VALUE</span><h1>钱包 / 储值管理</h1></div><button class="secondary" :disabled="busy" @click="refresh">刷新</button></div>
    <div class="wallet-tabs" role="tablist"><button v-for="item in [['adjust','手动调账'],['codes','储值码'],['ledger','资金流水']]" :key="item[0]" role="tab" :aria-selected="tab === item[0]" :class="{ active: tab === item[0] }" @click="tab = item[0]">{{ item[1] }}</button></div>
    <p v-if="error" class="wallet-error" role="alert">{{ error }}</p><p v-if="success" class="wallet-success" role="status">{{ success }}</p>
    <div v-if="tab === 'adjust'" class="wallet-admin-grid"><form class="wallet-form" @submit.prevent="adjust"><h2>调整用户余额</h2><p class="wallet-muted">每笔调整都会记录操作人、原因和调整后的余额。</p><label>用户名<input v-model="adjustment.username" list="wallet-users" required maxlength="50" placeholder="输入或选择用户名"><datalist id="wallet-users"><option v-for="account in users" :key="account.id" :value="account.username">{{ money(account.balance) }}</option></datalist></label><label>操作<select v-model="adjustment.direction"><option value="add">增加余额</option><option value="deduct">扣减余额</option></select></label><label>金额（元）<input v-model="adjustment.amount" inputmode="decimal" placeholder="0.00" required maxlength="11"></label><label>调账原因<textarea v-model="adjustment.remark" maxlength="500" rows="3" required placeholder="请填写可核对的原因"></textarea></label><button class="primary" :disabled="busy">{{ busy ? '正在提交…' : '确认调账' }}</button></form>
      <section class="wallet-form wallet-users-panel" aria-labelledby="wallet-users-heading">
        <header class="wallet-users-heading"><h2 id="wallet-users-heading">用户余额</h2><span>{{ users.length }} 位用户</span></header>
        <form class="wallet-search" @submit.prevent="loadUsers"><input v-model="query" aria-label="搜索用户名" placeholder="搜索用户名" maxlength="50"><button class="secondary">查询</button></form>
        <div class="wallet-accounts">
          <div v-if="users.length" class="wallet-accounts-head"><span>用户</span><span>可用余额</span></div>
          <button v-for="account in users" :key="account.id" type="button" :class="{ 'is-selected': adjustment.username === account.username }" :aria-pressed="adjustment.username === account.username" @click="adjustment.username = account.username">
            <span class="wallet-account-details"><span class="wallet-account-name" :title="account.username">{{ account.username }}</span><small :class="{ 'is-disabled': account.status !== 'ACTIVE' }">{{ account.status === 'ACTIVE' ? '正常' : '已禁用' }}</small></span><strong>{{ money(account.balance) }}</strong>
          </button>
          <p v-if="!users.length" class="wallet-accounts-empty">没有找到匹配的用户</p>
        </div>
      </section></div>
<div v-else-if="tab === 'codes'"><form class="wallet-form wallet-batch" @submit.prevent="generate"><label>储值面额（元）<input v-model="batch.amount" type="text" inputmode="decimal" maxlength="11" placeholder="请输入面额，如 25.50" required><small class="wallet-muted">大于 0，最多两位小数</small></label><label>生成数量<input v-model.number="batch.quantity" type="number" min="1" max="100" step="1" required></label><button class="primary" :disabled="busy">{{ busy ? '正在生成…' : '生成储值码' }}</button></form><div v-if="latestBatch.length" class="wallet-batch-result" role="status"><div><strong>本批已生成 {{ latestBatch.length }} 张 · {{ money(latestBatch[0].amount) }}</strong><p>仅复制最近一次生成的充值码。</p></div><button class="secondary" :disabled="!latestBatch.some(c => c.status === 0 && c.code)" @click="copy(latestBatch.filter(c => c.status === 0 && c.code).map(c => `${c.code}  ${money(c.amount)}`).join('\n'))">复制本批充值码</button></div><div class="wallet-section-heading"><p class="wallet-muted">充值码等同于储值凭证，请勿公开。已使用的码不再显示。</p><button class="secondary" :disabled="!codes.some(c => c.status === 0)" @click="copy(codes.filter(c => c.status === 0).map(c => `${c.code}  ${money(c.amount)}`).join('\n'))">复制当前列表未使用码</button></div><div class="data-panel"><table><thead><tr><th>储值码</th><th>面额</th><th>状态</th><th>使用人 / 时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in codes" :key="item.id"><td><code>{{ item.code || '已隐藏' }}</code></td><td>{{ money(item.amount) }}</td><td>{{ item.status === 0 ? '未使用' : '已使用' }}</td><td>{{ item.used_by || '—' }}<small>{{ time(item.used_at) }}</small></td><td><button v-if="item.status === 0" class="text-button" @click="copy(item.code)">复制</button></td></tr><tr v-if="!codes.length"><td colspan="5">暂无储值码</td></tr></tbody></table></div><button v-if="moreCodes" class="secondary" @click="more('codes')">加载更多充值码</button></div>
    <div v-else><form class="wallet-search" @submit.prevent="refresh"><input v-model="filter" placeholder="按完整用户名筛选流水" maxlength="50"><button class="secondary">查询</button></form><div class="data-panel"><table><thead><tr><th>用户 / 时间</th><th>类型 / 订单</th><th>变动</th><th>变动后余额</th><th>备注</th><th>操作人</th></tr></thead><tbody><tr v-for="item in ledger" :key="item.id"><td>{{ item.username }}<small>{{ time(item.created_at) }}</small></td><td>{{ labels[item.type] }}<small v-if="item.related_order_no">#{{ item.related_order_no }}</small></td><td :class="Number(item.amount) > 0 ? 'wallet-credit' : 'wallet-debit'">{{ Number(item.amount) > 0 ? '+' : Number(item.amount) < 0 ? '−' : '' }}{{ money(Math.abs(Number(item.amount))) }}</td><td>{{ money(item.balance_after) }}</td><td class="wallet-remark">{{ item.remark }}</td><td>{{ item.operator || '—' }}</td></tr><tr v-if="!ledger.length"><td colspan="6">暂无资金流水</td></tr></tbody></table></div><button v-if="moreLedger" class="secondary" @click="more('ledger')">加载更多流水</button></div>
  </section>
</template>

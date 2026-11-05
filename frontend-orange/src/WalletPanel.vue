<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref } from 'vue'
const props = defineProps({ balance: { default: null }, errorText: Function })
const emit = defineEmits(['close', 'updated'])
const api = inject('api'), code = ref(''), transactions = ref([]), busy = ref(false), loading = ref(false), error = ref(''), success = ref(''), more = ref(false)
const drawer = ref(null)
let previousFocus, previousOverflow
const money = value => `¥${Number(value || 0).toFixed(2)}`
const balanceParts = computed(() => Number(props.balance || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).split('.'))
const labels = { RECHARGE: '储值充值', CONSUME: '订单支付', REFUND: '订单退款', ADMIN_ADJUST: '店长调账' }
const time = value => new Date(value).toLocaleString('zh-CN', { hour12: false })
async function load(append = false) {
  loading.value = true; error.value = ''
  try {
    const rows = (await api.get('/wallet/transactions', { params: append ? { before: transactions.value.at(-1).id } : {} })).data
    transactions.value = append ? [...transactions.value, ...rows] : rows; more.value = rows.length === 30
    if (!append) emit('updated', (await api.get('/wallet')).data.balance)
  } catch (e) { error.value = props.errorText(e) } finally { loading.value = false }
}
async function redeem() {
  if (busy.value) return
  if (!code.value.trim()) { error.value = '请输入储值卡 / 兑换码'; return }
  busy.value = true; error.value = ''; success.value = ''
  try {
    const data = (await api.post('/wallet/redeem', { code: code.value.trim() })).data
    emit('updated', data.balance); code.value = ''; success.value = `兑换成功，${money(data.amount)} 已到账`; await load()
  } catch (e) { error.value = props.errorText(e) } finally { busy.value = false }
}
function trapFocus(event) {
  if (event.key !== 'Tab') return
  const elements = [...drawer.value.querySelectorAll('button:not(:disabled),input:not(:disabled),[tabindex="0"]')]
  const first = elements[0], last = elements.at(-1)
  if (event.shiftKey && (document.activeElement === first || document.activeElement === drawer.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
onMounted(async () => {
  previousFocus = document.activeElement; previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden'
  await nextTick(); drawer.value?.focus(); load()
})
onUnmounted(() => { document.body.style.overflow = previousOverflow; previousFocus?.focus() })
</script>

<template>
  <div class="wallet-backdrop" @click.self="emit('close')" @keydown.esc="emit('close')">
    <section ref="drawer" class="wallet-drawer" role="dialog" aria-modal="true" aria-labelledby="wallet-title" tabindex="-1" @keydown="trapFocus">
      <div class="wallet-heading"><div><span class="overline">YOUR WALLET</span><h1 id="wallet-title">我的钱包</h1></div><button class="secondary" aria-label="关闭钱包" @click="emit('close')">关闭 ×</button></div>
      <article class="wallet-asset"><span>当前可用余额</span><strong class="wallet-amount" :aria-label="balance === null ? '余额加载中' : `余额 ${money(balance)}`"><template v-if="balance !== null"><span class="wallet-amount-currency" aria-hidden="true">¥</span><span class="wallet-amount-value" aria-hidden="true">{{ balanceParts[0] }}<span class="wallet-amount-decimals">.{{ balanceParts[1] }}</span></span></template><span v-else aria-hidden="true">—</span></strong><small>NOVAMART / 为下一份喜欢，留一点余地。</small></article>
      <form class="wallet-redeem" @submit.prevent="redeem"><label for="redeem-code">储值卡 / 兑换码</label><div><input id="redeem-code" v-model="code" maxlength="60" placeholder="输入储值卡 / 兑换码" autocomplete="off" spellcheck="false" :disabled="busy"><button class="primary" :disabled="busy || !code.trim()">{{ busy ? '正在兑换…' : '立即兑换' }}</button></div><p>需要获取储值码？请联系主理人购买。</p></form>
      <p v-if="error" class="wallet-error" role="alert">{{ error }}</p><p v-if="success" class="wallet-success" role="status">{{ success }}</p>
      <div class="wallet-section-heading"><h2>收支明细</h2><button class="text-button" :disabled="loading" @click="load()">刷新</button></div>
      <p v-if="!transactions.length && !loading" class="wallet-muted">暂无收支记录</p><p v-if="loading" class="wallet-muted">正在加载…</p>
      <ol class="wallet-ledger"><li v-for="item in transactions" :key="item.id"><div><span class="wallet-tag">{{ labels[item.type] }}</span><time>{{ time(item.created_at) }}</time><p>{{ item.remark }}</p><small v-if="item.related_order_no">订单 #{{ item.related_order_no }} · </small><small>余额 {{ money(item.balance_after) }}</small></div><strong :class="Number(item.amount) > 0 ? 'wallet-credit' : 'wallet-debit'">{{ Number(item.amount) > 0 ? '+' : Number(item.amount) < 0 ? '−' : '' }}{{ money(Math.abs(Number(item.amount))) }}</strong></li></ol>
      <button v-if="more" class="secondary wide" :disabled="loading" @click="load(true)">加载更多明细</button>
    </section>
  </div>
</template>
